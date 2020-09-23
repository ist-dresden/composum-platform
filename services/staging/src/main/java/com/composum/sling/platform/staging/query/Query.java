package com.composum.sling.platform.staging.query;

import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import com.composum.sling.platform.staging.query.impl.StagingQueryImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_PATH;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.NT_BASE;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Allows querying resources with a fluent API , transparently supporting querying the released versions of resources
 * when used on a {@link StagingResourceResolver}.
 * Create this with {@link QueryBuilder#makeQuery(ResourceResolver)}.
 * <p>
 * For better overview, this class contains just the builder-specific stuff - the actual SQL statements are created and executed in the implementation.
 *
 * @author Hans-Peter Stoerr
 */
public abstract class Query {

    /**
     * Various modi of the SQL2 generation. For internal use only.
     */
    public static enum QueryGenerationMode {
        /**
         * Query outside version storage and release workspace copy.
         */
        NORMAL,
        /**
         * Query in release workspace copy
         */
        WORKSPACECOPY,
        /**
         * Query in version storage
         */
        VERSIONSTORAGE
    }

    /**
     * Virtual column that returns the path of the resource for {@link #selectAndExecute(String...)}.
     */
    public static final String COLUMN_PATH = JcrConstants.JCR_PATH;

    /**
     * Virtual column that returns the score of the resource for {@link #selectAndExecute(String...)}.
     */
    public static final String COLUMN_SCORE = JcrConstants.JCR_SCORE;

    /**
     * Virtual column that returns the excerpt of the resource for {@link #selectAndExecute(String...)} according to
     * fulltext searches.
     *
     * @see QueryConditionDsl.QueryConditionBuilder#contains(String)
     * @see QueryConditionDsl.QueryConditionBuilder#contains(String, String)
     */
    public static final String COLUMN_EXCERPT = "rep:excerpt";

    protected static final Logger LOG = getLogger(Query.class);

    @CheckForNull
    protected QueryConditionDsl.QueryConditionImpl queryCondition;
    protected String path;
    @CheckForNull
    protected String element;
    @CheckForNull
    protected String orderBy;
    @CheckForNull
    protected String typeConstraint;
    @CheckForNull
    protected String[] selectColumns;
    protected boolean ascending = true;
    protected char nextJoinSelector = 'o';
    final protected List<JoinData> joins = new ArrayList<>();
    protected long limit = Long.MAX_VALUE;
    protected long offset = 0;

    /**
     * Mandatory absolute path below which we query descendant resources.
     * As with SQL2 ISDESCENDANTNODE or XPATH /somewhere//* this finds only descendants, not the node at path itself.
     *
     * @return this for chaining calls in builder-style
     */
    public Query path(String path) {
        Validate.isTrue(path.startsWith("/"), "only absolute paths are supported, but called with %s", path);
        this.path = path;
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if ("".equals(path)) path = null;
        return this;
    }

    /**
     * Restricts the name of nodes to return. <br>
     * <b>Caution</b>: for StagingResourceResolver, this doesn't work reliably for
     * the site name and also not for names of versionables.
     *
     * @return this for chaining calls in builder-style
     */
    public Query element(String name) {
        this.element = name;
        return this;
    }

    /**
     * XPATH like 'element(name or '*',type)' condition - element(name) + type(type)
     */
    @Nonnull
    public Query element(@Nullable final String name, @Nullable final String type) {
        if (StringUtils.isNotBlank(name) && !"*".equals(name)) {
            element(name);
        }
        if (StringUtils.isNotBlank(type)) {
            type(type);
        }
        return this;
    }

    /**
     * Sets the condition that restricts the found nodes. The condition is created with a builder {@link
     * #conditionBuilder()}.
     *
     * @param condition the result of building a condition, started from a {@link #conditionBuilder()}.
     * @return this for chaining calls in builder-style
     * @see #conditionBuilder()
     */
    public Query condition(QueryConditionDsl.QueryCondition condition) {
        this.queryCondition = (QueryConditionDsl.QueryConditionImpl) condition;
        return this;
    }

    /**
     * Returns a builder that can be used to create the condition for
     * {@link #condition(QueryConditionDsl.QueryCondition)}.
     * The created condition is reuseable independently of this {@link Query} (but not unmodifiable), and has an effect
     * on the Query only when you call {@link #condition(QueryConditionDsl.QueryCondition)} with it.
     *
     * @see QueryConditionDsl#builder()
     */
    public QueryConditionDsl.QueryConditionBuilder conditionBuilder() {
        return new QueryConditionDsl("n").builder();
    }

    /**
     * Sets a condition that restricts the found nodes via a join. There are only joins from the found nodes to children
     * or descendants of the found node supported.
     * <p>
     * Caution: when specifying a exactPrimaryType, this needs to match exactly the jcr:primaryType of the joined nodes.
     * Subtypes or nodes with this as a mixin cannot be supported with sensible cost on releases (that is, frozen
     * nodes), and cannot easily be filtered afterwards from the result set since it would be difficult to keep the
     * semantics of outer joins.
     *
     * @param type                the type of join
     * @param joinCondition       the join condition: join on descendants or children
     * @param exactPrimaryType    optional, the exact primary type of the joined nodes. Subtypes / mixins will not be
     *                            found - please leave empty if there are various types possible.
     * @param joinSelectCondition the join select condition created with {@link #joinConditionBuilder()} - once for each
     *                            join
     * @return this for chaining calls in builder-style
     */
    public Query join(JoinType type, JoinCondition joinCondition, String exactPrimaryType,
                      QueryConditionDsl.QueryCondition joinSelectCondition) {
        for (JoinData data : joins)
            Validate.isTrue(!data.joinSelectCondition.getSelector().equals(joinSelectCondition.getSelector()),
                    "Join condition was already added to this query.");
        joins.add(new JoinData(type, joinCondition, exactPrimaryType, (QueryConditionDsl.QueryConditionImpl) joinSelectCondition));
        return this;
    }

    /**
     * Sets a condition that restricts the found nodes via a join. There are only joins from the found nodes to children
     * or descendants of the found node supported.
     *
     * @param type                the type of join
     * @param joinCondition       the join condition: join on descendants or children
     * @param joinSelectCondition the join select condition created with {@link #joinConditionBuilder()} - once for each
     *                            join
     * @return this for chaining calls in builder-style
     */
    public Query join(JoinType type, JoinCondition joinCondition,
                      QueryConditionDsl.QueryCondition joinSelectCondition) {
        return join(type, joinCondition, null, joinSelectCondition);
    }

    /**
     * Returns a builder that can be used to create the condition for
     * {@link #condition(QueryConditionDsl.QueryCondition)}.
     * The created condition should be used exactly once with {@link #join(JoinType, JoinCondition, String,
     * QueryConditionDsl.QueryCondition)} .
     *
     * @see QueryConditionDsl#builder()
     */
    public QueryConditionDsl.QueryConditionBuilder joinConditionBuilder() {
        return new QueryConditionDsl(String.valueOf(nextJoinSelector++)).builder();
    }

    /**
     * Optionally, sets an ordering of the found nodes.
     *
     * @return this for chaining calls in builder-style
     */
    public Query orderBy(String attribute) {
        this.orderBy = attribute;
        return this;
    }

    /**
     * Sets an ascending ordering.
     *
     * @return this for chaining calls in builder-style
     */
    public Query ascending() {
        this.ascending = true;
        return this;
    }

    /**
     * Sets an descending ordering.
     *
     * @return this for chaining calls in builder-style
     */
    public Query descending() {
        this.ascending = false;
        return this;
    }

    /**
     * Restricts the type of nodes to return.
     *
     * @return this for chaining calls in builder-style
     */
    public Query type(String type) {
        this.typeConstraint = type;
        return this;
    }

    /**
     * Sets the maximum size of the result set to <code>limit</code>.
     *
     * @param limit a <code>long</code>
     * @return this for chaining calls in builder-style
     */
    public Query limit(long limit) {
        Validate.isTrue(limit >= 0, "The limit should be nonnegative, but was %d", limit);
        this.limit = limit;
        return this;
    }

    /**
     * Sets the start offset of the result set to <code>offset</code>.
     *
     * @param offset a <code>long</code>
     * @return this for chaining calls in builder-style
     */
    public Query offset(long offset) {
        Validate.isTrue(limit >= 0, "The limit should be nonnegative, but was %d", limit);
        this.offset = offset;
        return this;
    }

    /**
     * Executes the query and returns the results as an {@link Iterable} of Resources.
     *
     * @return the result, not null
     * @throws SlingException       if an error occurs querying for the resources.
     * @throws QuerySyntaxException if the query is not syntactically correct. (Shouldn't rarely happen, because of
     *                              being a DSL and whatnot.)
     */
    @Nonnull
    public abstract Iterable<Resource> execute() throws SlingException, QuerySyntaxException;

    /**
     * Executes the query and returns the results as {@link Stream} of Resources.
     *
     * @return the result, not null
     * @throws SlingException       if an error occurs querying for the resources.
     * @throws QuerySyntaxException if the query is not syntactically correct. (Shouldn't rarely happen, because of
     *                              being a DSL and whatnot.)
     */
    @Nonnull
    public Stream<Resource> stream() throws SlingException, QuerySyntaxException {
        Iterable<Resource> resourceIterable = execute();
        Spliterator<Resource> spliterator = Spliterators.spliteratorUnknownSize(resourceIterable.iterator(),
                Spliterator.IMMUTABLE | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false);
    }

    /**
     * Executes the query and returns only the given columns of the node. It can return various pseudo-columns .
     *
     * @param columns property names of the searched nodes or pseudo-columns
     * @return not null
     * @throws SlingException       if an error occurs querying for the resources.
     * @throws QuerySyntaxException if the query is not syntactically correct. (Shouldn't rarely happen, because of
     *                              being a DSL and whatnot.)
     * @see #COLUMN_PATH
     * @see #COLUMN_SCORE
     * @see #COLUMN_EXCERPT
     */
    @Nonnull
    public abstract Iterable<QueryValueMap> selectAndExecute(String... columns) throws SlingException, QuerySyntaxException;

    /**
     * Implementation calls this before using the query to check whether it's in a sane state.
     */
    protected void validate() {
        Validate.notNull(path, "path is required");
    }

    @Nonnull
    protected String propertyConstraint(QueryGenerationMode mode) {
        if (queryCondition != null) {
            String sql2 = QueryGenerationMode.NORMAL == mode ? queryCondition.getSQL2() : queryCondition.getVersionedSQL2();
            return "AND (" + sql2 + ") \n";
        } else {
            return "";
        }
    }

    @Nonnull
    protected String elementConstraint(QueryGenerationMode mode) {
        if (isBlank(element)) return "";
        if (QueryGenerationMode.VERSIONSTORAGE == mode) return "AND (NAME(n) = '" + element + "' OR " +
                "(NAME(n) = 'jcr:frozenNode' AND history.default LIKE '%/" + element + "')) ";
        else return "AND NAME(n) = '" + element + "' ";
    }

    @Nonnull
    protected String orderByClause(QueryGenerationMode mode) {
        if (isBlank(orderBy)) return "";
        String direction = ascending ? "ASC" : "DESC";
        if (COLUMN_PATH.equals(orderBy) && QueryGenerationMode.VERSIONSTORAGE == mode)
            return "ORDER BY history.[default] " + direction + ", n.[" + orderBy + "] " + direction + " \n";
        String attr = QueryGenerationMode.NORMAL == mode ? orderBy : StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(orderBy, orderBy);
        return "ORDER BY n.[" + attr + "] " + direction + " \n";
    }

    @Nonnull
    protected String orderBySelect() {
        return isBlank(orderBy) ? "" : ", n.[" + orderBy + "] AS [query:orderBy] ";
    }

    @Nonnull
    protected String additionalSelect() {
        if (null == selectColumns) return "";
        StringBuilder buf = new StringBuilder();
        for (String column : selectColumns) {
            if (COLUMN_PATH.equals(column) || column.endsWith(".jcr:path") || column.endsWith(".[jcr:path]")) continue;
            buf.append(", ");
            if (COLUMN_EXCERPT.equals(column)) buf.append("excerpt(n)");
            else if (column.contains(".[")) buf.append(column);
            else buf.append("n.[").append(column).append("]");
            buf.append(" ");
        }
        return buf.toString();
    }

    protected String joinSelects() {
        if (joins.isEmpty()) return "";
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(", ").append(join.getSelector()).append(".[").append(JCR_PATH).append("] ");
        }
        return buf.toString();
    }

    protected String joins(StagingQueryImpl.QueryGenerationMode mode) {
        if (joins.isEmpty()) return "";
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(join.join(mode));
        }
        return buf.toString();
    }

    protected String joinSelectConditions(QueryGenerationMode mode) {
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(join.primaryTypeCondition(mode));
            buf.append("AND (").append(join.selectCondition(mode)).append(") ");
        }
        return buf.toString();
    }

    @Override
    public String toString() {
        ToStringBuilder toStringBuilder = new ToStringBuilder(this);
        addToStringAttributes(toStringBuilder);
        return toStringBuilder.toString();
    }

    protected void addToStringAttributes(ToStringBuilder toStringBuilder) {
        toStringBuilder.append("path", path);
        if (element != null) toStringBuilder.append("element", element);
        if (queryCondition != null) toStringBuilder.append("queryCondition", queryCondition);
        if (typeConstraint != null) toStringBuilder.append("typeConstraint", typeConstraint);
        if (selectColumns != null && selectColumns.length > 0) toStringBuilder.append("selectColumns", selectColumns);
        toStringBuilder.append("ascending", ascending);
        if (orderBy != null) toStringBuilder.append("orderBy", orderBy);
        if (!joins.isEmpty()) toStringBuilder.append("joins", joins);
        if (limit != Long.MAX_VALUE) toStringBuilder.append("limit", limit);
        if (offset != 0) toStringBuilder.append("offset", offset);
    }

    public enum JoinType {Inner, LeftOuter, RightOuter}

    public enum JoinCondition {
        /**
         * Specifies that the joined node is a descendant. Caution: this fails if it crosses the boundary into a
         * versionable - that is, the boundary between the release tree and the versioned documents in version storage.
         */
        Descendant,
        /**
         * Specifies that the joined node is a child. Caution: this fails if it crosses the boundary into a
         * versionable - that is, the boundary between the release tree and the versioned documents in version storage,
         * and if it crosses the boundary from outside the release tree into the release tree.
         */
        Child
    }

    protected class JoinData {
        final JoinType type;
        final JoinCondition joinCondition;
        public final QueryConditionDsl.QueryConditionImpl joinSelectCondition;
        final String exactPrimaryType;

        public JoinData(JoinType type, JoinCondition joinCondition, String exactPrimaryType,
                        QueryConditionDsl.QueryConditionImpl joinSelectCondition) {
            this.type = type;
            this.joinCondition = joinCondition;
            this.joinSelectCondition = joinSelectCondition;
            this.exactPrimaryType = exactPrimaryType;
        }

        public String getSelector() {
            return joinSelectCondition.getSelector();
        }

        public String join(QueryGenerationMode mode) {
            StringBuilder buf = new StringBuilder();
            if (JoinType.Inner == type) buf.append("INNER JOIN");
            if (JoinType.RightOuter == type) buf.append("RIGHT OUTER JOIN");
            if (JoinType.LeftOuter == type) buf.append("LEFT OUTER JOIN");
            buf.append(" [");
            buf.append(QueryGenerationMode.VERSIONSTORAGE == mode ? JcrConstants.NT_FROZENNODE : defaultIfBlank(exactPrimaryType, NT_BASE));
            buf.append("] AS ").append(joinSelectCondition.getSelector()).append(" ON ");
            if (JoinCondition.Descendant == joinCondition) buf.append("ISDESCENDANTNODE");
            if (JoinCondition.Child == joinCondition) buf.append("ISCHILDNODE");
            buf.append("(").append(getSelector()).append(", n)\n");
            return buf.toString();
        }

        public String primaryTypeCondition(QueryGenerationMode mode) {
            if (isBlank(exactPrimaryType)) return "";
            if (QueryGenerationMode.NORMAL == mode)
                return "AND " + getSelector() + ".[" + JCR_PRIMARYTYPE + "]='" + exactPrimaryType + "' ";
            else
                return "AND " + getSelector() + ".[" + JCR_FROZENPRIMARYTYPE + "]='" + exactPrimaryType + "' ";
        }

        public String selectCondition(QueryGenerationMode mode) {
            return QueryGenerationMode.NORMAL == mode ? joinSelectCondition.getSQL2() : joinSelectCondition.getVersionedSQL2();
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("type", type)
                    .append("joinCondition", joinCondition)
                    .append("datatype", exactPrimaryType)
                    .append("joinSelectCondition", joinSelectCondition)
                    .toString();
        }
    }

}

package com.composum.sling.platform.staging.query;

import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.jackrabbit.JcrConstants.*;
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

    /** Virtual column that returns the path of the resource for {@link #selectAndExecute(String...)}. */
    public static final String COLUMN_PATH = JcrConstants.JCR_PATH;

    /** Virtual column that returns the score of the resource for {@link #selectAndExecute(String...)}. */
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
     * Sets the absolute path from which we query child resources. Mandatory.
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
     * Restricts the name of nodes to return.
     *
     * @return this for chaining calls in builder-style
     */
    public Query element(String element) {
        this.element = element;
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
                      QueryConditionDsl.QueryConditionImpl joinSelectCondition) {
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

    /** Executes the query and returns the results as Resources. */
    @Nonnull
    public abstract Iterable<Resource> execute() throws RepositoryException;

    /**
     * Executes the query and returns only the given columns of the node. It can return various pseudo-columns .
     *
     * @param columns property names of the searched nodes or pseudo-columns
     * @return not null
     * @see #COLUMN_PATH
     * @see #COLUMN_SCORE
     * @see #COLUMN_EXCERPT
     */
    @Nonnull
    public abstract Iterable<QueryValueMap> selectAndExecute(String... columns) throws RepositoryException;

    /** Implementation calls this before using the query to check whether it's in a sane state. */
    protected void validate() {
        Validate.notNull(path, "path is required");
    }

    @Nonnull
    protected String propertyConstraint(boolean isInVersionSpace) {
        if (queryCondition != null) {
            String sql2 = isInVersionSpace ? queryCondition.getVersionedSQL2() : queryCondition.getSQL2();
            return "AND (" + sql2 + ") \n";
        } else {
            return "";
        }
    }

    @Nonnull
    protected String elementConstraint(boolean versioned) {
        if (isBlank(element)) return "";
        if (versioned) return "AND (NAME(n) = '" + element + "' OR " +
                "(NAME(n) = 'jcr:frozenNode' AND history.default LIKE '%/" + element + "')) ";
        else return "AND NAME(n) = '" + element + "' ";
    }

    @Nonnull
    protected String orderByClause(boolean versioned) {
        if (isBlank(orderBy)) return "";
        String direction = ascending ? "ASC" : "DESC";
        if (COLUMN_PATH.equals(orderBy) && versioned)
            return "ORDER BY history.[default] " + direction + ", n.[" + orderBy + "] " + direction + " \n";
        return "ORDER BY n.[" + orderBy + "] " + direction + " \n";
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

    protected String joinSelects(boolean versioned) {
        if (joins.isEmpty()) return "";
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(", ").append(join.getSelector()).append(".[").append(JCR_PATH).append("] ");
        }
        return buf.toString();
    }

    protected String joins(boolean versioned) {
        if (joins.isEmpty()) return "";
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(join.join(versioned));
        }
        return buf.toString();
    }

    protected String joinSelectConditions(boolean versioned) {
        StringBuilder buf = new StringBuilder();
        for (JoinData join : joins) {
            buf.append(join.primaryTypeCondition(versioned));
            buf.append("AND (").append(join.selectCondition(versioned)).append(") ");
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
        toStringBuilder
                // .append("release", release)
                .append("queryCondition", queryCondition)
                .append("path", path)
                .append("element", element)
                .append("orderBy", orderBy)
                .append("typeConstraint", typeConstraint)
                .append("ascending", ascending)
                .append("joins", joins);
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

        public String join(boolean versioned) {
            StringBuilder buf = new StringBuilder();
            if (JoinType.Inner == type) buf.append("INNER JOIN");
            if (JoinType.RightOuter == type) buf.append("RIGHT OUTER JOIN");
            if (JoinType.LeftOuter == type) buf.append("LEFT OUTER JOIN");
            buf.append(" [");
            buf.append(versioned ? JcrConstants.NT_FROZENNODE : defaultIfBlank(exactPrimaryType, NT_BASE));
            buf.append("] AS ").append(joinSelectCondition.getSelector()).append(" ON ");
            if (JoinCondition.Descendant == joinCondition) buf.append("ISDESCENDANTNODE");
            if (JoinCondition.Child == joinCondition) buf.append("ISCHILDNODE");
            buf.append("(").append(getSelector()).append(", n)\n");
            return buf.toString();
        }

        public String primaryTypeCondition(boolean versioned) {
            if (isBlank(exactPrimaryType)) return "";
            if (versioned) return "AND " + getSelector() + ".[" + JCR_FROZENPRIMARYTYPE + "]='" + exactPrimaryType +
                    "' ";
            else return "AND " + getSelector() + ".[" + JCR_PRIMARYTYPE + "]='" + exactPrimaryType + "' ";
        }

        public String selectCondition(boolean versioned) {
            return versioned ? joinSelectCondition.getVersionedSQL2() : joinSelectCondition.getSQL2();
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

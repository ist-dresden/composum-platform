package com.composum.sling.platform.staging.query;

import com.composum.sling.platform.staging.StagingException;
import com.composum.sling.platform.staging.StagingResource;
import com.composum.sling.platform.staging.StagingResourceResolver;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import org.apache.commons.collections4.ComparatorUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.*;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.util.*;

import static javax.jcr.query.Query.JCR_SQL2;
import static org.apache.commons.collections4.ComparatorUtils.*;
import static org.apache.commons.collections4.IteratorUtils.*;
import static org.apache.commons.lang3.StringUtils.*;
import static org.apache.jackrabbit.JcrConstants.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Allows querying resources with a fluent API , transparently supporting querying the released versions of resources
 * when used on a {@link StagingResourceResolver}.
 *
 * @author Hans-Peter Stoerr
 */
public class Query {

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
    protected final String releasedLabel;
    @Nullable
    protected final ReleaseMapper releaseMapper;
    @Nonnull
    protected final ResourceResolver resourceResolver;
    protected final Map<String, Boolean> matchesTypeConstraintCache = new HashMap<>();
    @CheckForNull
    protected QueryConditionDsl.QueryCondition queryCondition;
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
    protected List<JoinData> joins = new ArrayList<>();
    protected long limit = Long.MAX_VALUE;
    protected long offset = 0;

    /**
     * Constructor called by {@link QueryBuilder}. We use {@link StagingResourceResolver#adaptTo(Class)} to find out
     * whether we have a StagingResourceResolver, since it might be wrapped.
     */
    Query(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
        StagingResourceResolver stagingResolver = resourceResolver.adaptTo(StagingResourceResolver.class);
        this.releasedLabel = null != stagingResolver ? stagingResolver.getReleasedLabel() : null;
        this.releaseMapper = null != stagingResolver ? stagingResolver.getReleaseMapper() : null;
    }

    @Nonnull
    protected static List<String> readRowIterator(RowIterator rowIterator, String columnName) throws
            RepositoryException {
        List<String> res = new ArrayList<>();
        while (rowIterator.hasNext()) {
            res.add(rowIterator.nextRow().getValue(columnName).getString());
        }
        return res;
    }

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
        this.queryCondition = condition;
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
        joins.add(new JoinData(type, joinCondition, exactPrimaryType, joinSelectCondition));
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
        matchesTypeConstraintCache.clear();
        return this;
    }

    /**
     * Sets the maximum size of the result set to <code>limit</code>.
     *
     * @param limit a <code>long</code>
     * @return this for chaining calls in builder-style
     */
    public Query setLimit(long limit) {
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
    public Query setOffset(long offset) {
        Validate.isTrue(limit >= 0, "The limit should be nonnegative, but was %d", limit);
        this.offset = offset;
        return this;
    }


    /** Executes the query and returns the results as Resources. */
    @Nonnull
    public Iterable<Resource> execute() throws RepositoryException {
        selectColumns = null;
        return asIterable(extractResources(executeInternal()));
    }

    protected void validate() {
        Validate.notNull(path, "path is required");
    }

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
    public Iterable<QueryValueMap> selectAndExecute(String... columns) throws RepositoryException {
        this.selectColumns = columns;
        return asIterable(extractColumns(executeInternal(), columns));
    }

    protected Iterator<Row> executeInternal() throws RepositoryException {
        if (0 == limit) return emptyIterator();

        Iterator<Row> rows;
        validate();
        final Session session = resourceResolver.adaptTo(Session.class);
        final Workspace workspace = session.getWorkspace();
        final QueryManager queryManager = workspace.getQueryManager();

        if (isBlank(releasedLabel)) {
            String statement = buildSQL24();
            LOG.debug("JCR-SQL2:\n{}", statement);
            javax.jcr.query.Query query = initJcrQuery(queryManager, statement);
            query.setOffset(offset);
            if (0 < limit && Long.MAX_VALUE != limit) query.setLimit(limit);
            QueryResult queryResult = query.execute();
            rows = queryResult.getRows();
        } else {
            // first check if the path is in the version storage
            String pathToUse = searchpathForPathPrefixInVersionStorage(queryManager);

            String statement = null != pathToUse ? buildSQL24SingleVersion(pathToUse) : buildSQL24Version();
            LOG.debug("JCR-SQL2 versioned:\n{}", statement);
            javax.jcr.query.Query query = initJcrQuery(queryManager, statement);
            if (0 <= limit && Long.MAX_VALUE != limit) query.setLimit(offset + limit);
            QueryResult queryResult = query.execute();
            Iterator<Row> rowsFromVersionStorage = queryResult.getRows();
            rowsFromVersionStorage = filterOnlyReleaseMappedAndByType(rowsFromVersionStorage);

            statement = buildSQL24WithVersioncheck();
            LOG.debug("JCR-SQL2 unversioned:\n{}", statement);
            query = initJcrQuery(queryManager, statement);
            if (0 <= limit && Long.MAX_VALUE != limit) query.setLimit(offset + limit);
            queryResult = query.execute();
            Iterator<Row> rowsOutsideVersionStorage = queryResult.getRows();
            rowsOutsideVersionStorage = filterNotReleaseMappedOrUnversioned(rowsOutsideVersionStorage);

            if (null == orderBy) rows = chainedIterator(rowsOutsideVersionStorage, rowsFromVersionStorage);
            else rows = collatedIterator(orderByRowComparator(), rowsOutsideVersionStorage, rowsFromVersionStorage);
            rows = ensureLimitAndOffset(rows);
        }
        return rows;
    }

    /**
     * For queries within releases the results of two iterators are joined together. Thus, we have to observe offset and
     * limit afterwards.
     */
    protected Iterator<Row> ensureLimitAndOffset(Iterator<Row> rows) {
        return 0 < offset ? boundedIterator(rows, offset, limit) : boundedIterator(rows, limit);
    }

    protected javax.jcr.query.Query initJcrQuery(QueryManager queryManager, String statement)
            throws RepositoryException {
        javax.jcr.query.Query query = queryManager.createQuery(statement, JCR_SQL2);
        if (null != queryCondition) queryCondition.applyBindingValues(query, resourceResolver);
        for (JoinData join : joins) join.joinSelectCondition.applyBindingValues(query, resourceResolver);
        return query;
    }

    /** The current version of a resource is used if it is not versioned, or if it is not releasemapped. */
    protected Iterator<Row> filterNotReleaseMappedOrUnversioned(Iterator<Row> rowsOutsideVersionStorage) {
        Predicate<Row> filter = new Predicate<Row>() {
            @Override
            public boolean evaluate(Row row) {
                boolean isVersioned = isNotBlank(getString(row, "query:versionMarker1")) || isNotBlank(getString(row,
                        "query:versionMarker2"));
                return !isVersioned || !releaseMapper.releaseMappingAllowed(getString(row, "n.jcr:path"));
            }
        };
        return IteratorUtils.filteredIterator(rowsOutsideVersionStorage, filter);
    }

    /** A historic version of a resource is only used if it is releasemapped. */
    protected Iterator<Row> filterOnlyReleaseMappedAndByType(Iterator<Row> rowsFromVersionStorage) {
        Predicate<Row> filter = new Predicate<Row>() {
            @Override
            public boolean evaluate(Row row) {
                String frozenPath = getString(row, "n.jcr:path");
                String originalPath = getString(row, "query:originalPath");
                String path = originalPath +
                        frozenPath.substring(frozenPath.indexOf(JCR_FROZENNODE) + JCR_FROZENNODE.length());
                if (!releaseMapper.releaseMappingAllowed(path)) return false;
                if (null == typeConstraint) return true;
                try {
                    return hasAppropriateType(frozenPath, getString(row, "query:type"),
                            getString(row, "query:mixin"));
                } catch (RepositoryException e) {
                    throw new StagingException(e);
                }
            }
        };
        return IteratorUtils.filteredIterator(rowsFromVersionStorage, filter);
    }

    protected boolean hasAppropriateType(String frozenPath, String type, String mixin) throws RepositoryException {
        if (matchesTypeConstraint(type)) return true;
        if (null != mixin) {
            if (matchesTypeConstraint(mixin)) return true;
            // since JCR Queries can't return multiple values 8-() we need to query the node in case there are
            // several mixins. Only the first one is returned from the query. OUCH!
            Node node = resourceResolver.adaptTo(Session.class).getNode(frozenPath);
            Value[] mixins = node.getProperty(JcrConstants.JCR_FROZENMIXINTYPES).getValues();
            for (Value mixinValue : mixins) {
                if (matchesTypeConstraint(mixinValue.getString())) return true;
            }
        }
        return false;
    }

    protected boolean matchesTypeConstraint(String type) throws RepositoryException {
        Boolean result = matchesTypeConstraintCache.get(type);
        if (null == result) {
            NodeTypeManager nodeTypeManager = resourceResolver.adaptTo(Session.class).getWorkspace()
                    .getNodeTypeManager();
            NodeType nodeType = nodeTypeManager.getNodeType(type);
            result = typeConstraint.equals(nodeType.getName());
            for (NodeType superType : nodeType.getSupertypes()) {
                result = result || typeConstraint.equals(superType.getName());
            }
            matchesTypeConstraintCache.put(type, result);
            LOG.debug("Type constraint {} matches={}", type, result);
        }
        return result;
    }

    /** Mimics the JCR Query orderBy comparison. */
    protected Comparator<Row> orderByRowComparator() {
        if (COLUMN_PATH.equals(orderBy)) return pathComparator();

        Transformer<Row, Value> extractOrderBy = new Transformer<Row, Value>() {
            @Override
            public Value transform(Row row) {
                try {
                    return row.getValue("query:orderBy");
                } catch (RepositoryException e) {
                    throw new StagingException(e);
                }
            }
        };
        return transformedComparator(ValueComparatorFactory.makeComparator(ascending), extractOrderBy);
    }

    protected Comparator<Row> pathComparator() {
        Transformer<Row, String> extractPath = new Transformer<Row, String>() {
            @Override
            public String transform(Row row) {
                try {
                    String path = row.getValue("n.jcr:path").getString();
                    try {
                        String originalPath = row.getValue("query:originalPath").getString();
                        path = originalPath +
                                path.substring(path.indexOf(JCR_FROZENNODE) + JCR_FROZENNODE.length());
                    } catch (RepositoryException e) {
                        // OK, row is not from versioned query
                    }
                    return path;
                } catch (RepositoryException e) {
                    throw new StagingException(e);
                }
            }
        };
        Comparator<Row> comparator = nullHighComparator(
                transformedComparator(ComparatorUtils.<String>naturalComparator(), extractPath));
        if (!ascending) comparator = reversedComparator(comparator);
        return comparator;
    }

    protected String getString(Row row, String columnname) {
        try {
            Value value = row.getValue(columnname);
            if (null != value) return value.getString();
            return null;
        } catch (RepositoryException e) {
            throw new StagingException(e);
        }
    }

    /** Queries non-versioned resources. The query returns the paths of the found resources. */
    @Nonnull
    protected String buildSQL24() {
        String type = StringUtils.defaultString(typeConstraint, "nt:base");
        return "SELECT n.[jcr:path] " + orderBySelect() + additionalSelect() + joinSelects(false) + "\n" +
                "FROM [" + type + "] AS n \n" +
                joins(false) +
                "WHERE ISDESCENDANTNODE(n, '" + path + "') \n" +
                elementConstraint(false) +
                propertyConstraint(false) +
                joinSelectConditions(false) +
                orderByClause(false);
    }

    /**
     * Queries resources outside the version storage. The query returns the path, versionMarker1 and versionMarker2 as
     * something where one of them is not null when the resource is versioned, and the ordering clause if orderBy is
     * requested. The results are ordered by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24WithVersioncheck() {
        String type = StringUtils.defaultString(typeConstraint, "nt:base");
        return "SELECT n.[jcr:path], " +
                "n.[jcr:versionHistory] AS [query:versionMarker1], " +
                "versioned.[jcr:primaryType] AS [query:versionMarker2] " +
                orderBySelect() + additionalSelect() + joinSelects(false) + "\n" +
                "FROM [" + type + "] AS n \n" +
                "LEFT OUTER JOIN [mix:versionable] AS versioned ON ISDESCENDANTNODE(n, versioned) \n" +
                joins(false) +
                "WHERE ISDESCENDANTNODE(n, '" + path + "') " +
                elementConstraint(false) +
                propertyConstraint(false) +
                joinSelectConditions(false) +
                orderByClause(false);
    }

    /**
     * Queries resources in the version storage. The query returns the path and the ordering clause if orderBy is
     * requested, paths to calculate the location of the original resource and type information. The results are ordered
     * by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24Version() {
        return "SELECT n.[jcr:path], history.[default] AS [query:originalPath], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + joinSelects(true) + "\n" +
                "FROM [nt:versionHistory] AS history \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON version.[jcr:uuid] = labels.[" + releasedLabel + "] \n" +
                "INNER JOIN [nt:frozenNode] AS n ON ISDESCENDANTNODE(n, version) \n" +
                joins(true) +
                "WHERE ISDESCENDANTNODE(history, '/jcr:system/jcr:versionStorage') \n" +
                "AND history.[default] like '" + path + "/%" + "' \n" +
                elementConstraint(true) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                joinSelectConditions(true) +
                orderByClause(true);
    }

    /**
     * Queries resources in the version storage of a single version history. The query returns the path and the ordering
     * clause if orderBy is requested, paths to calculate the location of the original resource and type information.
     * The results are ordered by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24SingleVersion(String pathInsideVersion) {
        return "SELECT n.[jcr:path], history.[default] AS [query:originalPath], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + "\n" +
                "FROM [nt:frozenNode] AS n \n" +
                "INNER JOIN [nt:versionHistory] as history ON ISDESCENDANTNODE(n, history) \n" +
                "WHERE ISDESCENDANTNODE(n, '" + pathInsideVersion + "') \n" +
                elementConstraint(true) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                orderByClause(true);
    }

    /**
     * Searches for prefixes of the given path that are / have been in version storage and have something for the given
     * release. If we find something, this is the single repository we have to query, and we return the absolute path
     * that is the search root for our query, otherwise null .
     */
    @CheckForNull
    protected String searchpathForPathPrefixInVersionStorage(QueryManager queryManager)
            throws RepositoryException {
        String pathToTest = path + "/";
        StringBuilder statement = new StringBuilder("SELECT history.default AS default, " +
                "labels.[" + releasedLabel + "] as uuid \n" +
                "FROM [nt:versionHistory] as history \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON ISCHILDNODE(labels, history) \n" +
                "WHERE labels.[" + releasedLabel + "] IS NOT NULL AND (");
        int pos = 0;
        boolean first = true;
        while (0 <= (pos = pathToTest.indexOf('/', pos + 1))) {
            if (!first) statement.append(" OR ");
            statement.append(" history.default = '").append(pathToTest.substring(0, pos)).append("'");
            first = false;
        }
        statement.append(")");
        LOG.debug("Check version SQL2:\n{}", statement);
        RowIterator rowIterator = queryManager.createQuery(statement.toString(), JCR_SQL2).execute().getRows();
        if (!rowIterator.hasNext()) return null;
        Row row = rowIterator.nextRow();
        String pathPrefix = row.getValue("default").getString();
        String versionUuid = row.getValue("uuid").getString();
        Node node = resourceResolver.adaptTo(Session.class).getNodeByIdentifier(versionUuid);
        String versionPath = node.getPath();
        String pathToSearch = versionPath + "/" + JCR_FROZENNODE + path.substring(pathPrefix.length());
        if (rowIterator.hasNext()) LOG.warn("Unsupported: several prefixes of {} are versioned: {}",
                pathToTest, Arrays.asList(pathPrefix, rowIterator.nextRow().getPath("default")));
        return pathToSearch;
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

    protected Iterator<Resource> extractResources(Iterator<Row> rows) throws RepositoryException {
        Transformer<Row, Resource> transformer = new Transformer<Row, Resource>() {
            @Override
            public Resource transform(Row input) {
                return getResource(input, "n");
            }
        };
        return IteratorUtils.transformedIterator(rows, transformer);
    }

    protected Iterator<QueryValueMap> extractColumns(Iterator<Row> rows, final String[] columns) {
        Transformer<Row, QueryValueMap> transformer = new Transformer<Row, QueryValueMap>() {
            @Override
            public QueryValueMap transform(Row input) {
                return new QueryValueMap(Query.this, input, columns);
            }
        };
        return IteratorUtils.transformedIterator(rows, transformer);
    }

    /**
     * Shortcut to create the resource that skips several here unnecessary steps in {@link
     * StagingResourceResolver#getResource(String)}.
     */
    protected Resource getResource(Row input, String selector) {
        try {
            Value value = input.getValue(selector + ".jcr:path");
            if (null == value) return null;
            final String path = value.getString();
            if (resourceResolver instanceof StagingResourceResolver) {
                // for speed, skips various checks by the resolver that aren't needed here
                StagingResourceResolver stagingResolver = (StagingResourceResolver) resourceResolver;
                final Resource resource;
                resource = stagingResolver.getDelegateeResourceResolver().getResource(path);
                return StagingResource.wrap(resource, stagingResolver);
            } else {
                return resourceResolver.getResource(path);
            }
        } catch (RepositoryException e) {
            throw new StagingException(e);
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("releasedLabel", releasedLabel)
                .append("queryCondition", queryCondition)
                .append("path", path)
                .append("element", element)
                .append("orderBy", orderBy)
                .append("typeConstraint", typeConstraint)
                .append("ascending", ascending)
                .append("joins", joins)
                .toString();
    }

    public enum JoinType {Inner, LeftOuter, RightOuter}

    public enum JoinCondition {Descendant, Child}

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

    protected class JoinData {
        final JoinType type;
        final JoinCondition joinCondition;
        final QueryConditionDsl.QueryCondition joinSelectCondition;
        final String exactPrimaryType;

        public JoinData(JoinType type, JoinCondition joinCondition, String exactPrimaryType,
                        QueryConditionDsl.QueryCondition joinSelectCondition) {
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

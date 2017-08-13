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
import static org.apache.commons.collections4.IteratorUtils.*;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.LoggerFactory.getLogger;

public class Query {

    protected static final Logger LOG = getLogger(Query.class);

    @CheckForNull
    protected final String releasedLabel;
    @Nullable
    protected final ReleaseMapper releaseMapper;

    @CheckForNull
    protected QueryConditionDsl.QueryCondition queryCondition;
    protected String path;
    @CheckForNull
    protected String element;
    @CheckForNull
    protected String orderBy;
    @CheckForNull
    protected String typeConstraint;
    protected boolean ascending = true;

    protected final ResourceResolver resourceResolver;

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
     * Sets the condition that restricts the found nodes. The condition is created with a builder
     * {@link #conditionBuilder()}.
     *
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
     *
     * @see QueryConditionDsl#builder()
     */
    public QueryConditionDsl.ConstraintStart conditionBuilder() {
        return QueryConditionDsl.builder();
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

    protected void validate() {
        Validate.notNull(path, "path is required");
    }

    public Iterable<Resource> execute() throws RepositoryException {
        validate();
        final Session session = resourceResolver.adaptTo(Session.class);
        final Workspace workspace = session.getWorkspace();
        final QueryManager queryManager = workspace.getQueryManager();

        Iterable<Resource> result;
        if (isBlank(releasedLabel)) {

            String statement = buildSQL24();
            LOG.debug("JCR-SQL2:\n{}", statement);
            javax.jcr.query.Query query = queryManager.createQuery(statement, JCR_SQL2);
            if (null != queryCondition) queryCondition.applyBindingValues(query, resourceResolver);
            QueryResult queryResult = query.execute();
            result = asIterable(extractResources(queryResult.getRows()));

        } else {

            // first check if the path is in the version storage
            String pathToUse = searchpathForPathPrefixInVersionStorage(queryManager);

            String statement = null != pathToUse ? buildSQL24SingleVersion(pathToUse) : buildSQL24Version();
            LOG.debug("JCR-SQL2 versioned:\n{}", statement);
            javax.jcr.query.Query query = queryManager.createQuery(statement, JCR_SQL2);
            if (null != queryCondition) queryCondition.applyBindingValues(query, resourceResolver);
            Iterator<Row> rowsFromVersionStorage = query.execute().getRows();
            rowsFromVersionStorage = filterOnlyReleaseMappedAndByType(rowsFromVersionStorage);

            statement = buildSQL24WithVersioncheck();
            LOG.debug("JCR-SQL2 versioned:\n{}", statement);
            query = queryManager.createQuery(statement, JCR_SQL2);
            if (null != queryCondition) queryCondition.applyBindingValues(query, resourceResolver);
            Iterator<Row> rowsOutsideVersionStorage = query.execute().getRows();
            rowsOutsideVersionStorage = filterNotReleaseMappedOrUnversioned(rowsOutsideVersionStorage);

            Iterator<Row> rows;
            if (null == orderBy) rows = chainedIterator(rowsOutsideVersionStorage, rowsFromVersionStorage);
            else rows = collatedIterator(orderByRowComparator(), rowsOutsideVersionStorage, rowsFromVersionStorage);
            return asIterable(extractResources(rows));
        }
        return result;
    }

    /** The current version of a resource is used if it is not versioned, or if it is not releasemapped. */
    protected Iterator<Row> filterNotReleaseMappedOrUnversioned(Iterator<Row> rowsOutsideVersionStorage) {
        Predicate<Row> filter = new Predicate<Row>() {
            @Override
            public boolean evaluate(Row row) {
                boolean isVersioned = isNotBlank(getString(row, "versionMarker1")) || isNotBlank(getString(row,
                        "versionMarker2"));
                return !isVersioned || !releaseMapper.releaseMappingAllowed(getString(row, "path"));
            }
        };
        return IteratorUtils.filteredIterator(rowsOutsideVersionStorage, filter);
    }

    /** A historic version of a resource is only used if it is releasemapped. */
    protected Iterator<Row> filterOnlyReleaseMappedAndByType(Iterator<Row> rowsFromVersionStorage) {
        Predicate<Row> filter = new Predicate<Row>() {
            @Override
            public boolean evaluate(Row row) {
                String frozenPath = getString(row, "path");
                String originalPath = getString(row, "originalPath");
                String path = originalPath +
                        frozenPath.substring(frozenPath.indexOf("jcr:frozenNode") + 14);
                if (!releaseMapper.releaseMappingAllowed(path)) return false;
                if (null == typeConstraint) return true;
                try {
                    return hasAppropriateType(frozenPath, getString(row, "type"), getString(row, "mixin"));
                } catch (RepositoryException e) {
                    throw new StagingException(e);
                }
            }
        };
        return IteratorUtils.filteredIterator(rowsFromVersionStorage, filter);
    }

    private boolean hasAppropriateType(String frozenPath, String type, String mixin) throws RepositoryException {
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

    protected Map<String, Boolean> matchesTypeConstraintCache = new HashMap<>();

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
        Transformer<Row, Value> extractOrderBy = new Transformer<Row, Value>() {
            @Override
            public Value transform(Row row) {
                try {
                    return row.getValue("orderBy");
                } catch (RepositoryException e) {
                    throw new StagingException(e);
                }
            }
        };
        return ComparatorUtils.transformedComparator(ValueComparatorFactory.makeComparator(ascending), extractOrderBy);
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
        return "SELECT n.[jcr:path] AS path \n" +
                "FROM [" + type + "] as n \n" +
                "WHERE ISDESCENDANTNODE(n, '" + path + "') \n" +
                elementConstraint() +
                propertyConstraint(false) +
                orderByClause();
    }

    /**
     * Queries resources outside the version storage. The query returns the path, versionMarker1 and versionMarker2 as
     * something where one of them is not null when the resource is versioned, and the ordering clause if orderBy is
     * requested. The results are ordered by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24WithVersioncheck() {
        String type = StringUtils.defaultString(typeConstraint, "nt:base");
        return "SELECT n.[jcr:path] AS path, " +
                "n.[jcr:versionHistory] AS versionMarker1, versioned.[jcr:primaryType] AS versionMarker2 " +
                orderBySelector() + "\n" +
                "FROM [" + type + "] as n \n" +
                "LEFT OUTER JOIN [mix:versionable] AS versioned ON ISDESCENDANTNODE(n, versioned) \n" +
                "WHERE ISDESCENDANTNODE(n, '" + path + "') " +
                elementConstraint() +
                propertyConstraint(false) +
                orderByClause();
    }

    /**
     * Queries resources in the version storage. The query returns the path and the ordering clause if orderBy is
     * requested, paths to calculate the location of the original resource and type information. The results are ordered
     * by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24Version() {
        return "SELECT n.[jcr:path] AS path, history.[default] AS originalPath, " +
                "n.[jcr:frozenPrimaryType] AS type, n.[jcr:frozenMixinTypes] as mixin" +
                orderBySelector() + " \n" +
                "FROM [nt:versionHistory] as history \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON version.[jcr:uuid] = labels.[" + releasedLabel + "] \n" +
                "INNER JOIN [nt:frozenNode] AS release ON ISCHILDNODE(release, version) \n" +
                "INNER JOIN [nt:frozenNode] AS n ON ISDESCENDANTNODE(n, release) \n" +
                "WHERE ISDESCENDANTNODE(history, '/jcr:system/jcr:versionStorage') \n" +
                "AND history.[default] like '" + path + "/%" + "' AND NAME(release)='jcr:frozenNode' \n" +
                elementConstraint() +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                orderByClause();
    }

    /**
     * Queries resources in the version storage of a single version history. The query returns the path and the ordering
     * clause if orderBy is requested, paths to calculate the location of the original resource and type information.
     * The results are ordered by {@link #orderBy(String)}, anyway, to make merging with other results easy.
     */
    @Nonnull
    protected String buildSQL24SingleVersion(String pathInsideVersion) {
        return "SELECT n.[jcr:path] AS path, history.[default] AS originalPath, " +
                "n.[jcr:frozenPrimaryType] AS type, n.[jcr:frozenMixinTypes] as mixin" +
                orderBySelector() + " \n" +
                "FROM [nt:frozenNode] AS n \n" +
                "INNER JOIN [nt:versionHistory] as history ON ISDESCENDANTNODE(n, history) \n" +
                "WHERE ISDESCENDANTNODE(n, '" + pathInsideVersion + "') \n" +
                elementConstraint() +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                orderByClause();
    }

    /**
     * Searches for prefixes of the given path that are / have been in version storage and have something for the
     * given release. If we find something, this is the single repository we have to query, and we return
     * the absolute path that is the search root for our query, otherwise null .
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
        String pathToSearch = versionPath + "/" + JcrConstants.JCR_FROZENNODE + path.substring(pathPrefix.length());
        if (rowIterator.hasNext()) LOG.warn("Unsupported: several prefixes of {} are versioned: {}",
                pathToTest, Arrays.asList(pathPrefix, rowIterator.nextRow().getPath("default")));
        return pathToSearch;
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
    protected String elementConstraint() {
        return isBlank(element) ? "" : "AND NAME(n) = '" + element + "'";
    }

    @Nonnull
    protected String orderByClause() {
        return isBlank(orderBy) ? "" : "ORDER BY n.[" + orderBy + "] " + (ascending ? "ASC" : "DESC") + " \n";
    }

    @Nonnull
    protected String orderBySelector() {
        return isBlank(orderBy) ? "" : ", n.[" + orderBy + "] AS orderBy ";
    }

    protected Iterator<Resource> extractResources(Iterator<Row> rows) throws RepositoryException {
        Transformer<Row, Resource> transformer = new Transformer<Row, Resource>() {
            @Override
            public Resource transform(Row input) {
                try {
                    final String path = input.getValue("path").getString();
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
        };
        return IteratorUtils.transformedIterator(rows, transformer);
    }

}

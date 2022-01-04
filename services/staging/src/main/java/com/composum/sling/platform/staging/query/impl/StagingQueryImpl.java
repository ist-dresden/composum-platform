package com.composum.sling.platform.staging.query.impl;

import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import com.composum.sling.platform.staging.impl.StagingUtils;
import com.composum.sling.platform.staging.query.Query;
import com.composum.sling.platform.staging.query.QueryValueMap;
import org.apache.commons.collections4.ComparatorUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;
import static com.composum.sling.platform.staging.StagingConstants.PROP_DEACTIVATED;
import static com.composum.sling.platform.staging.StagingConstants.PROP_VERSION;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_VERSIONREFERENCE;
import static com.composum.sling.platform.staging.query.Query.QueryGenerationMode.NORMAL;
import static com.composum.sling.platform.staging.query.Query.QueryGenerationMode.VERSIONSTORAGE;
import static com.composum.sling.platform.staging.query.Query.QueryGenerationMode.WORKSPACECOPY;
import static javax.jcr.query.Query.JCR_SQL2;
import static org.apache.commons.collections4.ComparatorUtils.nullHighComparator;
import static org.apache.commons.collections4.ComparatorUtils.reversedComparator;
import static org.apache.commons.collections4.ComparatorUtils.transformedComparator;
import static org.apache.commons.collections4.IteratorUtils.asIterable;
import static org.apache.commons.collections4.IteratorUtils.boundedIterator;
import static org.apache.commons.collections4.IteratorUtils.chainedIterator;
import static org.apache.commons.collections4.IteratorUtils.collatedIterator;
import static org.apache.commons.collections4.IteratorUtils.emptyIterator;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE;

/**
 * This contains the parts of the implementation to {@link com.composum.sling.platform.staging.query.Query} that are
 * very specific to the {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}.
 */
public class StagingQueryImpl extends Query {

    @Nullable
    protected final DefaultStagingReleaseManager.ReleaseImpl release;
    @NotNull
    protected final ReleaseMapper releaseMapper;
    @NotNull
    protected final ResourceResolver resourceResolver;

    /** Caches whether a type matches the requested type constraint. */
    protected final Map<String, Boolean> matchesTypeConstraintCache = new HashMap<>();

    /** Lazily initialized - use only {@link #giveVersionUuidToVersionReferenceUuidMap()}. */
    private HashMap<String, String> versionUuidToVersionReferencePathMap;

    // We use {@link StagingResourceResolver#adaptTo(Class)} to find out whether we have a StagingResourceResolver, since it might be wrapped.
    protected StagingQueryImpl(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
        StagingResourceResolver stagingResolver = resourceResolver.adaptTo(StagingResourceResolver.class);
        this.release = null != stagingResolver ? DefaultStagingReleaseManager.ReleaseImpl.unwrap(stagingResolver.getRelease()) : null;
        this.releaseMapper = null != stagingResolver ? stagingResolver.getReleaseMapper() : ReleaseMapper.ALLPERMISSIVE;
    }

    @Override
    public Query type(String type) {
        matchesTypeConstraintCache.clear();
        return super.type(type);
    }

    protected String getPath() {
        return path;
    }

    @Override
    protected void addToStringAttributes(ToStringBuilder toStringBuilder) {
        toStringBuilder.append("release", release);
        super.addToStringAttributes(toStringBuilder);
    }

    @Override
    @NotNull
    public Iterable<Resource> execute() throws SlingException, QuerySyntaxException {
        selectColumns = null;
        try {
            return asIterable(extractResources(executeInternal()));
        } catch (RepositoryException e) {
            throw new SlingException("Exception during query", e);
        }
    }

    @Override
    @NotNull
    public Iterable<QueryValueMap> selectAndExecute(String... columns) throws SlingException, QuerySyntaxException {
        this.selectColumns = columns;
        try {
            return asIterable(extractColumns(executeInternal(), columns));
        } catch (RepositoryException e) {
            throw new SlingException("Exception during query", e);
        }
    }

    protected Iterator<Row> executeInternal() throws RepositoryException, QuerySyntaxException {
        validate();
        if (limit <= 0) return emptyIterator();

        final Session session = resourceResolver.adaptTo(Session.class);
        final QueryManager queryManager = session.getWorkspace().getQueryManager();
        ResourceResolver underlyingResolver = release != null ? release.getReleaseRoot().getResourceResolver() : null;
        boolean withinRelease = release != null && release.appliesToPath(path);
        boolean mappingAllowed = withinRelease && releaseMapper.releaseMappingAllowed(path);
        boolean releaseIsWithinPath = release != null && isSameOrDescendant(path, release.getReleaseRoot().getPath());

        if (release == null
                || withinRelease && !mappingAllowed
                || !withinRelease && !releaseIsWithinPath) {
            String statement = buildSQL2(NORMAL);
            LOG.debug("JCR-SQL2 not versioncontrolled:\n{}", statement);
            return executeNotReleasecontrolledQuery(queryManager, statement);
        }
        // OK - we are within a release and some of the descendants of path would be mapped to a release

        if (withinRelease && mappingAllowed) { // look whether there is a version reference at or above the path
            String firstExistingParent = release.mapToContentCopy(path);
            Resource firstExistingParentResource = underlyingResolver.getResource(firstExistingParent);
            StringBuilder relativePath = new StringBuilder();
            while (firstExistingParentResource == null &&
                    isSameOrDescendant(release.getReleaseNode().getPath(), firstExistingParent)) {
                String[] pathAndName = ResourceUtil.splitPathAndName(firstExistingParent);
                if (relativePath.length() > 0) relativePath.insert(0, '/');
                relativePath.insert(0, pathAndName[1]);
                firstExistingParent = pathAndName[0];
                firstExistingParentResource = underlyingResolver.getResource(firstExistingParent);
            }
            ResourceHandle versionReference = ResourceHandle.use(firstExistingParentResource);
            if (versionReference.isOfType(StagingConstants.TYPE_VERSIONREFERENCE)) {
                // the path is at or within that item in the version storage
                if (versionReference.getProperty(PROP_DEACTIVATED, false))
                    return emptyIterator();
                Resource propertyResource = versionReference.getChild(PROP_VERSION);
                Resource version = ResourceUtil.getReferredResource(propertyResource);
                Resource frozenNode = version != null ? version.getChild(JCR_FROZENNODE) : version;
                frozenNode = frozenNode != null && relativePath.length() > 0 ? frozenNode.getChild(relativePath.toString()) : version;
                if (frozenNode == null)
                    return emptyIterator();
                String statement = buildSQL24SingleVersion(frozenNode.getPath());
                LOG.debug("JCR-SQL2 single version:\n{}", statement);
                Iterator<Row> rowsFromVersionStorage = executeNotReleasecontrolledQuery(queryManager, statement);
                return filterFromVersionStorage(rowsFromVersionStorage);
            }
        }

        // We need to do up to three queries whose results need to be merged:
        // - within version storage
        // - within the release content copy
        // - outside bot

        Iterator<Row> rowsFromVersionStorage;
        String versionStorageStatement = buildSQL2Version();
        try {
            LOG.debug("JCR-SQL2 versioned:\n{}", versionStorageStatement);
            javax.jcr.query.Query versionedQuery = initJcrQuery(queryManager, versionStorageStatement);
            if (0 <= limit && Long.MAX_VALUE != limit) versionedQuery.setLimit(offset + limit);
            rowsFromVersionStorage = versionedQuery.execute().getRows();
            rowsFromVersionStorage = filterFromVersionStorage(rowsFromVersionStorage);
        } catch (InvalidQueryException e) {
            throw new QuerySyntaxException(e.getMessage(), versionStorageStatement, JCR_SQL2, e);
        }

        Iterator<Row> rowsInsideReleasetree;
        String releaseTreeStatement = buildSQL2(WORKSPACECOPY);
        try {
            LOG.debug("JCR-SQL2 workspace copy:\n{}", releaseTreeStatement);
            javax.jcr.query.Query unversionedQuery = initJcrQuery(queryManager, releaseTreeStatement);
            if (0 <= limit && Long.MAX_VALUE != limit) unversionedQuery.setLimit(offset + limit);
            rowsInsideReleasetree = unversionedQuery.execute().getRows();
            rowsInsideReleasetree = filterReleaseWorkspaceCopy(rowsInsideReleasetree);
        } catch (InvalidQueryException e) {
            throw new QuerySyntaxException(e.getMessage(), releaseTreeStatement, JCR_SQL2, e);
        }

        Iterator<Row> rows = mergeResults(rowsFromVersionStorage, rowsInsideReleasetree);

        String outsideStatement = buildSQL2(NORMAL);
        Iterator<Row> rowsOutside;
        try {
            LOG.debug("JCR-SQL2 unversioned:\n{}", outsideStatement);
            javax.jcr.query.Query unversionedQuery = initJcrQuery(queryManager, outsideStatement);
            if (0 <= limit && Long.MAX_VALUE != limit) unversionedQuery.setLimit(offset + limit);
            rowsOutside = unversionedQuery.execute().getRows();
            rowsOutside = filterUnmapped(rowsOutside);
        } catch (InvalidQueryException e) {
            throw new QuerySyntaxException(e.getMessage(), outsideStatement, JCR_SQL2, e);
        }

        rows = mergeResults(rows, rowsOutside);

        rows = ensureLimitAndOffset(rows);
        return rows;
    }

    @NotNull
    protected Iterator<Row> mergeResults(Iterator<Row> rowsFromVersionStorage, Iterator<Row> rowsInsideReleasetree) {
        Iterator<Row> rows;
        if (null == orderBy) rows = chainedIterator(rowsInsideReleasetree, rowsFromVersionStorage);
        else rows = collatedIterator(orderByRowComparator(), rowsInsideReleasetree, rowsFromVersionStorage);
        return rows;
    }

    /** The simple case: when we don't have to care about releases at all. */
    protected Iterator<Row> executeNotReleasecontrolledQuery(QueryManager queryManager, String statement) throws RepositoryException, QuerySyntaxException {
        try {
            javax.jcr.query.Query query = initJcrQuery(queryManager, statement);
            query.setOffset(offset);
            if (0 < limit && Long.MAX_VALUE != limit) query.setLimit(limit);
            QueryResult queryResult = query.execute();
            return queryResult.getRows();
        } catch (InvalidQueryException e) {
            throw new QuerySyntaxException(e.getMessage(), statement, JCR_SQL2, e);
        }
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
        if (null != queryCondition)
            queryCondition.applyBindingValues(query, resourceResolver);
        for (JoinData join : joins)
            join.joinSelectCondition.applyBindingValues(query, resourceResolver);
        return query;
    }

    /** Maps versionUuids of the version references in the release to their paths. */
    protected Map<String, String> giveVersionUuidToVersionReferenceUuidMap() throws RepositoryException {
        if (versionUuidToVersionReferencePathMap == null) {
            versionUuidToVersionReferencePathMap = new HashMap<>();
            final Session session = resourceResolver.adaptTo(Session.class);
            final QueryManager queryManager = session.getWorkspace().getQueryManager();
            String statement = "SELECT [cpl:deactivated], [cpl:version], [jcr:path] FROM [cpl:VersionReference] " +
                    "WHERE ISDESCENDANTNODE('" + release.getWorkspaceCopyNode().getPath() + "')";
            RowIterator rows = queryManager.createQuery(statement, JCR_SQL2).execute().getRows();
            for (Row row : JcrIteratorUtil.asIterable(rows)) {
                Value deactivated = row.getValue(PROP_DEACTIVATED);
                if (deactivated != null && deactivated.getBoolean())
                    continue;
                versionUuidToVersionReferencePathMap.put(row.getValue(PROP_VERSION).getString(),
                        row.getValue(CoreConstants.JCR_PATH).getString());
            }
        }
        return versionUuidToVersionReferencePathMap;
    }

    /** Calculates the original path where a frozen node is in the release. */
    protected String calculateSimulatedPath(String versionUuid, String frozenPath) throws RepositoryException {
        String versionablePath = giveVersionUuidToVersionReferenceUuidMap().get(versionUuid);
        String unfrozenPath = versionablePath +
                frozenPath.substring(frozenPath.indexOf(JCR_FROZENNODE) + JCR_FROZENNODE.length());
        String originalPath = release.unmapFromContentCopy(unfrozenPath);
        return originalPath;
    }

    /**
     * Filters the hierarchynodes / not mapped results. Results from the release content copy are only taken if
     * release mapping is allowed for their original paths, results from the current content of the release are taken
     * if they are not release mapped, and outside the release all are taken.
     */
    protected Iterator<Row> filterUnmapped(Iterator<Row> rowsOutsideVersionStorage) {
        StagingResourceResolver stagingResourceResolver = resourceResolver.adaptTo(StagingResourceResolver.class);
        Predicate<Row> filter = row -> {
            String rowPath = getString(row, "n.jcr:path");
            boolean appliesToPath = release.appliesToPath(rowPath);
            boolean mappingAllowed = releaseMapper.releaseMappingAllowed(rowPath);
            boolean directlyMappedPath = stagingResourceResolver != null ? stagingResourceResolver.isDirectlyMappedPath(rowPath) : false;
            boolean inWorkspaceCopy = isSameOrDescendant(release.getWorkspaceCopyNode().getPath(), rowPath);
            boolean workspaceCopyIsMapped = releaseMapper.releaseMappingAllowed(release.unmapFromContentCopy(rowPath));
            boolean result = !appliesToPath ||
                    !mappingAllowed || directlyMappedPath ||
                    inWorkspaceCopy && workspaceCopyIsMapped;
            return result;
        };
        return IteratorUtils.filteredIterator(rowsOutsideVersionStorage, filter);
    }

    /**
     * Filters results from the release workspace copy wrt. release mapping and type constraint.
     */
    protected Iterator<Row> filterReleaseWorkspaceCopy(Iterator<Row> rowsFromVersionStorage) {
        Predicate<Row> filter = row -> {
            try {
                String pathInCopy = getString(row, "n.jcr:path");
                String path = release.unmapFromContentCopy(pathInCopy);
                if (!releaseMapper.releaseMappingAllowed(path)) return false;
                if (null == typeConstraint) return true;
                return hasAppropriateType(pathInCopy, getString(row, "query:type"),
                        getString(row, "query:mixin"));
            } catch (RepositoryException e) {
                throw new SlingException("Unexpected JCR exception", e);
            }
        };
        return IteratorUtils.filteredIterator(rowsFromVersionStorage, filter);
    }

    /**
     * Filters results from version storage wrt. path, release mapping and type constraint.
     * A historic version of a resource is only used if it is releasemapped.
     */
    protected Iterator<Row> filterFromVersionStorage(Iterator<Row> rowsFromVersionStorage) {
        Predicate<Row> filter = row -> {
            try {
                String frozenPath = getString(row, "n.jcr:path");
                String versionUuid = getString(row, "query:versionUuid");
                String simulatedPath = calculateSimulatedPath(versionUuid, frozenPath);
                if (!isSameOrDescendant(path, simulatedPath) ||
                        !releaseMapper.releaseMappingAllowed(simulatedPath)) return false;
                if (null == typeConstraint) return true;
                return hasAppropriateType(frozenPath, getString(row, "query:type"),
                        getString(row, "query:mixin"));
            } catch (RepositoryException e) {
                throw new SlingException("Unexpected JCR exception", e);
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
        if (StringUtils.isBlank(typeConstraint))
            return true;
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

        Transformer<Row, Value> extractOrderBy = row -> {
            try {
                return row.getValue("query:orderBy");
            } catch (RepositoryException e) {
                throw new SlingException("Unexpected JCR exception", e);
            }
        };
        return transformedComparator(ValueComparatorFactory.makeComparator(ascending), extractOrderBy);
    }

    protected Comparator<Row> pathComparator() {
        Transformer<Row, String> extractPath = row -> {
            try { // calculate path from query:versionUuid if it's in version storage
                String rowPath = row.getValue("n.jcr:path").getString();
                try {
                    String versionUuid = getString(row, "query:versionUuid");
                    rowPath = calculateSimulatedPath(versionUuid, rowPath);
                } catch (RepositoryException e) {
                    // OK, row is not from versioned query
                }
                return rowPath;
            } catch (RepositoryException e) {
                throw new SlingException("Unexpected JCR exception", e);
            }
        };
        Comparator<Row> comparator = nullHighComparator(
                transformedComparator(ComparatorUtils.naturalComparator(), extractPath));
        if (!ascending) comparator = reversedComparator(comparator);
        return comparator;
    }

    protected String getString(Row row, String columnname) {
        try {
            Value value = row.getValue(columnname);
            if (null != value) return value.getString();
            return null;
        } catch (RepositoryException e) {
            throw new SlingException("Unexpected JCR exception", e);
        }
    }

    /**
     * Queries non-versioned resources - depending on {inrelease} outside of the release tree or inside the workspace copy.
     * Outside the release tree we add a condition that the query does not reach into the release tree.
     * @param mode {@link QueryGenerationMode#NORMAL} for queries outside the release tree, {@link QueryGenerationMode#WORKSPACECOPY} within the release tree.
     */
    @NotNull
    protected String buildSQL2(QueryGenerationMode mode) {
        if (VERSIONSTORAGE == mode) throw new IllegalArgumentException("Illegal mode " + mode);
        String notReleaseTree = "";
        String querypath = path;
        if (null != release) {
            if (WORKSPACECOPY == mode) {
                // search whole content copy if path is outside release, or search only the mapped path if it's inside.
                querypath = isSameOrDescendant(release.getReleaseRoot().getPath(), this.path) ? release.mapToContentCopy(this.path) : release.getWorkspaceCopyNode().getPath();
            } else {
                notReleaseTree = "AND NOT ISDESCENDANTNODE(n, '" + release.getReleaseNode().getParent().getPath() + "')\n";
            }
        }
        String type = StringUtils.defaultString(typeConstraint, "nt:base");
        if (WORKSPACECOPY == mode) // the actual primary type / mixins are in jcr:frozen*
            type = JcrConstants.NT_UNSTRUCTURED;
        String frozenSelects = "";
        if (NORMAL != mode)
            frozenSelects = ", n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin]";
        return "SELECT n.[jcr:path]" + frozenSelects + orderBySelect() + additionalSelect() + joinSelects() + "\n" +
                "FROM [" + type + "] AS n \n" +
                joins(mode) +
                "WHERE (ISDESCENDANTNODE(n, '" + querypath + "') OR ISSAMENODE(n, '" + querypath + "' )) \n" +
                notReleaseTree +
                elementConstraint(mode) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(mode) +
                joinSelectConditions(mode) +
                orderByClause(mode);
    }

    /**
     * Queries resources in the version storage. The query returns the path and the ordering clause if orderBy is
     * requested, paths to calculate the location of the original resource and type information. The results are ordered
     * by {@link #orderBy(String)}. <p>
     * This query ignores the path since that information is not present in version storage, and type constraints, which cannot sensibly be checked there. So this must be filtered
     * afterwards. But we rely on the default path attribute at the version history pointing somewhere into the release tree,
     * and that the version is labelled with the release number.
     */
    @NotNull
    protected String buildSQL2Version() {
        return "SELECT n.[jcr:path], version.[jcr:uuid] AS [query:versionUuid], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + joinSelects() + "\n" +
                "FROM [nt:versionHistory] AS history \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON version.[jcr:uuid] = labels.[" + release.getReleaseLabel() + "] \n" +
                "INNER JOIN [nt:frozenNode] AS n ON ISDESCENDANTNODE(n, version) \n" +
                joins(VERSIONSTORAGE) +
                "WHERE ISDESCENDANTNODE(history, '/jcr:system/jcr:versionStorage') \n" +
                "AND history.[default] like '" + release.getReleaseRoot().getPath() + "/%" + "' \n" +
                elementConstraint(VERSIONSTORAGE) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(VERSIONSTORAGE) +
                joinSelectConditions(VERSIONSTORAGE) +
                orderByClause(VERSIONSTORAGE);
    }

    /**
     * Queries resources in the version storage of a single version history. The query returns the path and the ordering
     * clause if orderBy is requested, paths to calculate the location of the original resource and type information.
     * Being in version storage, this has the same limitations like {@link #buildSQL2Version()}.
     */
    @NotNull
    protected String buildSQL24SingleVersion(String pathInsideVersionStorage) {
        return "SELECT n.[jcr:path], version.[jcr:uuid] AS [query:versionUuid], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + "\n" +
                "FROM [nt:frozenNode] AS n \n" +
                "INNER JOIN [nt:versionHistory] as history ON ISDESCENDANTNODE(n, history) \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "WHERE ISDESCENDANTNODE(n, '" + pathInsideVersionStorage + "') \n" +
                elementConstraint(VERSIONSTORAGE) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(VERSIONSTORAGE) +
                orderByClause(VERSIONSTORAGE);
    }

    protected Iterator<Resource> extractResources(Iterator<Row> rows) {
        Transformer<Row, Resource> transformer = input -> getResource(input, "n");
        return IteratorUtils.filteredIterator(IteratorUtils.transformedIterator(rows, transformer), Objects::nonNull);
    }

    protected Iterator<QueryValueMap> extractColumns(Iterator<Row> rows, final String[] columns) {
        Transformer<Row, QueryValueMap> transformer =
                input -> new QueryValueMapImpl(StagingQueryImpl.this, input, columns);
        return IteratorUtils.filteredIterator(IteratorUtils.transformedIterator(rows, transformer),
                valuemap -> valuemap.getResource() != null);
    }

    /**
     * Shortcut to create the resource that skips several here unnecessary steps in {@link
     * StagingResourceResolver#getResource(String)}.
     */
    protected Resource getResource(Row input, String selector) {
        try {
            Value value = input.getValue(selector + ".jcr:path");
            if (null == value) return null;
            final String rowPath = value.getString();
            Resource resource;

            StagingResourceResolver stagingResolver = resourceResolver.adaptTo(StagingResourceResolver.class);
            if (stagingResolver != null) {
                String realPath = rowPath;
                if (StagingUtils.isInVersionStorage(rowPath)) {
                    String versionUuid = getString(input, "query:versionUuid");
                    realPath = calculateSimulatedPath(versionUuid, rowPath);
                } else if (isSameOrDescendant(release.getWorkspaceCopyNode().getPath(), rowPath)) {
                    realPath = release.unmapFromContentCopy(rowPath);
                }
                // for speed, skips various checks by the resolver that aren't needed here
                Resource underlyingResource = stagingResolver.getUnderlyingResolver().getResource(rowPath);
                if (underlyingResource.getResourceType().equals(TYPE_VERSIONREFERENCE))
                    return null;
                resource = stagingResolver.wrapIntoStagingResource(realPath, underlyingResource, null, false);
                return resource;
            } else {
                resource = resourceResolver.getResource(rowPath);
                return resource;
            }
        } catch (RepositoryException e) {
            throw new SlingException("Unexpected JCR exception", e);
        }
    }

}

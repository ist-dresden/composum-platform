package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
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
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.query.QueryManager;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.util.*;

import static javax.jcr.query.Query.JCR_SQL2;
import static org.apache.commons.collections4.ComparatorUtils.*;
import static org.apache.commons.collections4.IteratorUtils.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE;

/**
 * This contains the parts of the implementation to {@link com.composum.sling.platform.staging.query.Query} that are
 * very specific to the {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}.
 */
public class StagingQueryImpl extends Query {

    @CheckForNull
    protected final DefaultStagingReleaseManager.ReleaseImpl release;
    @Nullable
    protected final ReleaseMapper releaseMapper;
    @Nonnull
    protected final ResourceResolver resourceResolver;

    /** Caches whether a type matches the requested type constraint. */
    protected final Map<String, Boolean> matchesTypeConstraintCache = new HashMap<>();

    // We use {@link StagingResourceResolver#adaptTo(Class)} to find out whether we have a StagingResourceResolver, since it might be wrapped.
    protected StagingQueryImpl(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
        StagingResourceResolver stagingResolver = resourceResolver.adaptTo(StagingResourceResolver.class);
        this.release = null != stagingResolver ? DefaultStagingReleaseManager.ReleaseImpl.unwrap(stagingResolver.getRelease()) : null;
        this.releaseMapper = null != stagingResolver ? stagingResolver.getReleaseMapper() : null;
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

    @Nonnull
    protected static List<String> readRowIterator(RowIterator rowIterator, String columnName) throws
            RepositoryException {
        List<String> res = new ArrayList<>();
        while (rowIterator.hasNext()) {
            res.add(rowIterator.nextRow().getValue(columnName).getString());
        }
        return res;
    }

    /** Executes the query and returns the results as Resources. */
    @Override
    @Nonnull
    public Iterable<Resource> execute() throws RepositoryException {
        selectColumns = null;
        return asIterable(extractResources(executeInternal()));
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
    @Override
    @Nonnull
    public Iterable<QueryValueMap> selectAndExecute(String... columns) throws RepositoryException {
        this.selectColumns = columns;
        return asIterable(extractColumns(executeInternal(), columns));
    }

    protected Iterator<Row> executeInternal() throws RepositoryException {
        validate();
        if (0 >= limit) return emptyIterator();

        Iterator<Row> rows = null;
        // XXX(hps,2019-04-24) implement
        /* final Session session = resourceResolver.adaptTo(Session.class);
        final QueryManager queryManager = session.getWorkspace().getQueryManager();

        if (release == null) {
            String statement = buildSQL2();
            LOG.debug("JCR-SQL2:\n{}", statement);
            javax.jcr.query.Query query = initJcrQuery(queryManager, statement);
            query.setOffset(offset);
            if (0 < limit && Long.MAX_VALUE != limit) query.setLimit(limit);
            QueryResult queryResult = query.execute();
            rows = queryResult.getRows();
        } else {
            // first check if the path is in the version storage
            String pathToUse = searchpathForPathPrefixInVersionStorage(queryManager);

            String statement = null != pathToUse ? buildSQL24SingleVersion(pathToUse) : buildSQL2Version();
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
        } */
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
        if (null != queryCondition)
            queryCondition.applyBindingValues(query, resourceResolver);
        for (JoinData join : joins)
            join.joinSelectCondition.applyBindingValues(query, resourceResolver);
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
    protected Iterator<Row> filterOnlyReleaseMappedAndByType(Iterator<Row> rowsFromVersionStorage) throws RepositoryException {
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
                    throw new SlingException("Unexpected JCR exception", e);
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
                    throw new SlingException("Unexpected JCR exception", e);
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
                    throw new SlingException("Unexpected JCR exception", e);
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
            throw new SlingException("Unexpected JCR exception", e);
        }
    }

    /**
     * Queries non-versioned resources. If the path reaches into the release, we add a condition that both
     * the content and the release content of the selected release are found (but not the other releases) -
     * these need to be filtered afterwards.
     */
    @Nonnull
    protected String buildSQL2() {
        String notReleaseTree = "";
        String querypath = path;
        if (null != release) {
            if (SlingResourceUtil.isSameOrDescendant(path, release.getReleaseRoot().getPath())) {
                notReleaseTree = "( ISDESCENDANTNODE(n, '" + release.getWorkspaceCopyNode().getPath() + "') OR NOT " +
                        "ISDESCENDANTNODE(n, '" + release.getWorkspaceCopyNode().getParent().getParent().getPath() + "') )\n";
            } else if (SlingResourceUtil.isSameOrDescendant(release.getReleaseRoot().getPath(), path)
                    && (null == releaseMapper || releaseMapper.releaseMappingAllowed(path))) {
                // path goes into release and is therefore mapped the content copy
                if (releaseMapper.releaseMappingAllowed(path))
                    querypath = release.mapToContentCopy(path);
            }
        }
        String type = StringUtils.defaultString(typeConstraint, "nt:base");
        return "SELECT n.[jcr:path] " + orderBySelect() + additionalSelect() + joinSelects(false) + "\n" +
                "FROM [" + type + "] AS n \n" +
                joins(false) +
                "WHERE ISDESCENDANTNODE(n, '" + querypath + "') \n" +
                notReleaseTree +
                elementConstraint(false) +
                propertyConstraint(false) +
                joinSelectConditions(false) +
                orderByClause(false);
    }

    /**
     * Queries resources in the version storage. The query returns the path and the ordering clause if orderBy is
     * requested, paths to calculate the location of the original resource and type information. The results are ordered
     * by {@link #orderBy(String)}. <p>
     * This query ignores the path since that information is not present in version storage, and type constraints, which cannot sensibly be checked there. So this must be filtered
     * afterwards. But we rely on the default path attribute at the version history pointing somewhere into the release tree,
     * and that the version is labelled with the release number.
     */
    @Nonnull
    protected String buildSQL2Version() {
        return "SELECT n.[jcr:path], history.[default] AS [query:originalPath], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + joinSelects(true) + "\n" +
                "FROM [nt:versionHistory] AS history \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON version.[jcr:uuid] = labels.[" + release.getReleaseLabel() + "] \n" +
                "INNER JOIN [nt:frozenNode] AS n ON ISDESCENDANTNODE(n, version) \n" +
                joins(true) +
                "WHERE ISDESCENDANTNODE(history, '/jcr:system/jcr:versionStorage') \n" +
                "AND history.[default] like '" + release.getReleaseRoot().getPath() + "/%" + "' \n" +
                elementConstraint(true) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                joinSelectConditions(true) +
                orderByClause(true);
    }

    /**
     * Queries resources in the version storage of a single version history. The query returns the path and the ordering
     * clause if orderBy is requested, paths to calculate the location of the original resource and type information.
     * Being in version storage, this has the same limitations like {@link #buildSQL2Version()}.
     */
    @Nonnull
    protected String buildSQL24SingleVersion(String pathInsideVersionStorage) {
        return "SELECT n.[jcr:path], history.[default] AS [query:originalPath], " +
                "n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] " +
                orderBySelect() + additionalSelect() + "\n" +
                "FROM [nt:frozenNode] AS n \n" +
                "INNER JOIN [nt:versionHistory] as history ON ISDESCENDANTNODE(n, history) \n" +
                "WHERE ISDESCENDANTNODE(n, '" + pathInsideVersionStorage + "') \n" +
                elementConstraint(true) +
                // deliberately no typeConstraint() since we need to check for subtypes, too
                propertyConstraint(true) +
                orderByClause(true);
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
                return new QueryValueMapImpl(StagingQueryImpl.this, input, columns);
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
                resource = stagingResolver.getUnderlyingResolver().getResource(path);
                return stagingResolver.wrapIntoStagingResource(path, resource, null, false);
            } else {
                return resourceResolver.getResource(path);
            }
        } catch (RepositoryException e) {
            throw new SlingException("Unexpected JCR exception", e);
        }
    }

}

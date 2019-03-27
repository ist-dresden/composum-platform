package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.QueryBuilderImpl;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;
import javax.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import static com.composum.sling.platform.staging.StagingUtils.isInVersionStorage;
import static com.composum.sling.platform.staging.StagingUtils.isRoot;
import static com.composum.sling.platform.staging.StagingUtils.isUnderVersionControl;
import static com.composum.sling.platform.staging.StagingUtils.isVersionable;
import static javax.jcr.query.Query.XPATH;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_SYSTEM;
import static org.apache.jackrabbit.JcrConstants.JCR_VERSIONLABELS;
import static org.apache.jackrabbit.JcrConstants.JCR_VERSIONSTORAGE;
import static org.apache.jackrabbit.JcrConstants.NT_VERSIONHISTORY;

/** Resolver that provides a view to the frozen nodes of a release as if they were the original resources. */
public class StagingResourceResolver implements ResourceResolver {

    /**
     * Find all nt:versionHistory in version store that has a label for a specified release and matches a
     * specified condition.
     */
    private static final String VERSION_STORAGE_QUERY_TEMPLATE = "/jcr:root/" + JCR_SYSTEM + "/" + JCR_VERSIONSTORAGE
            + "//*[@" + JCR_PRIMARYTYPE + "='" + NT_VERSIONHISTORY + "' and ./" + JCR_VERSIONLABELS + "/@%s and (%s)]";

    /** Condition where the content of property 'default' is equal to a given path. */
    private static final String VERSION_STORAGE_QUERY_CONDITIONTEMPLATE = "@default='%s'";

    /** find all nt:versionHistory in version store, where the content of property 'default' starts with a given path */
    private static final String VERSION_STORAGE_LIKE_QUERY = "/jcr:root/" + JCR_SYSTEM + "/" + JCR_VERSIONSTORAGE +
            "//*[@" + JCR_PRIMARYTYPE + "='" + NT_VERSIONHISTORY + "' and jcr:like(@default, '%s/%%')]";

    private static Logger LOGGER = LoggerFactory.getLogger(StagingResourceResolver.class);

    /**
     * The original ResourceResolver used to delegate calls to.
     */
    @Nonnull
    private final ResourceResolver resourceResolver;

    /**
     * ResourceResolverFactory injected into the SlingFilter. Can be used to create new default ResourceResolver.
     */
    @Nonnull
    private final ResourceResolverFactory resourceResolverFactory;

    /**
     * Name of the version label indicating the released version.
     */
    @Nonnull
    private final String releasedLabel;

    /**
     * the release mapping configuration.
     */
    @Nonnull
    private final ReleaseMapper releaseMapper;

    /**
     * Constructs a new StagingResourceResolver.
     *
     * @param resourceResolverFactory a ResourceResolverFactory used to clone a ResourceResolver
     * @param resourceResolver        the previous resourceResolver used for chaining
     * @param releasedLabel           the label indicating a released version in the version storage
     */
    public StagingResourceResolver(@Nonnull final ResourceResolverFactory resourceResolverFactory,
                                   @Nonnull final ResourceResolver resourceResolver,
                                   @Nonnull final String releasedLabel,
                                   @Nonnull final ReleaseMapper mapper) {
        this.resourceResolver = Objects.requireNonNull(resourceResolver);
        this.resourceResolverFactory = Objects.requireNonNull(resourceResolverFactory);
        this.releasedLabel = Objects.requireNonNull(releasedLabel);
        this.releaseMapper = Objects.requireNonNull(mapper);
    }

    @Nonnull
    public String getReleasedLabel() {
        return releasedLabel;
    }

    @Nonnull
    public ReleaseMapper getReleaseMapper() {
        return releaseMapper;
    }

    /**
     * Get the default ResourceResolver that is used to delegate calls to, to get the standard behavior.
     *
     * @return the ResourceResolver
     */
    @Nonnull
    public ResourceResolver getDelegateeResourceResolver() {
        return resourceResolver;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public Resource resolve(@Nonnull final HttpServletRequest request, @Nonnull final String absPath) {
        LOGGER.debug("resolve({}, {})", request, absPath);
        final Resource resource = resourceResolver.resolve(request, absPath);
        return getReleasedResource((SlingHttpServletRequest) request, resource, this.releasedLabel);
    }

    /**
     * Get the released version of a given resource.
     *
     * @param resource the resource of which the released version should be found
     * @return the released version or NonExistingResource
     */
    @Nonnull
    protected Resource getReleasedResource(final Resource resource) {
        return getReleasedResource(null, resource, this.releasedLabel);
    }

    @Nonnull
    private Resource getReleasedResource(@Nullable final SlingHttpServletRequest request, final Resource resource,
                                         final String releasedLabel) {
        if (!releaseMapper.releaseMappingAllowed(Objects.requireNonNull(resource).getPath())) {
            return resource;
        }
        try {
            final JackrabbitSession session = (JackrabbitSession) resourceResolver.adaptTo(Session.class);
            final VersionManager versionManager = session.getWorkspace().getVersionManager();
            final Node node = resource.adaptTo(Node.class);
            if (ResourceUtil.isNonExistingResource(resource) || node != null && isVersionable(node)) {
                // the resource is not existing. but maybe in an older version
                // or it is an existing node and we try to find the released version
                final String frozenNodePath = getReleasedFrozenNodePath(resource, releasedLabel, versionManager);
                if (null == frozenNodePath) return new NonExistingResource(this, resource.getPath());
                final Resource frozenResource = resourceResolver.getResource(frozenNodePath);
                return frozenResource == null
                        ? new NonExistingResource(this, resource.getPath())
                        : StagingResource.wrap(request, frozenResource, this);
            }

            String intermediatePath;
            Resource resourceToUse;
            if (node != null) { // it's a node but not direct versionable. try it's parents
                intermediatePath = "";
                resourceToUse = resource;
            } else { // it's not a node but a property. try it's parent[s]
                intermediatePath = resource.getName();
                resourceToUse = resource.getParent();
            }

            while (!isVersionable(resourceToUse) && !isRoot(resourceToUse)) {
                if (intermediatePath.length() == 0) {
                    intermediatePath = resourceToUse.getName();
                } else {
                    intermediatePath = resourceToUse.getName() + "/" + intermediatePath;
                }
                resourceToUse = resourceToUse.getParent();
            }

            if (isRoot(resourceToUse)) { // not versionable and no versionable parent
                return StagingResource.wrap(request, resource, this);
            } else {
                final String frozenNodePath = getReleasedFrozenNodePath(resourceToUse, releasedLabel,
                        versionManager);
                if (null == frozenNodePath) return new NonExistingResource(this, resource.getPath());
                final Resource frozenResource = resourceResolver.getResource(frozenNodePath + "/" + intermediatePath);
                return frozenResource == null
                        ? new NonExistingResource(this, frozenNodePath + "/" + intermediatePath)
                        : StagingResource.wrap(request, frozenResource, this);
            }
        } catch (RepositoryException e) { // shouldn't happen
            throw new StagingException(e);
        }
    }

    /**
     * Returns the path of the frozen node for the versioned resource that matches the releasedLabel.
     *
     * @return null if not found . This resource does not neccesarrily need to exist, if the path is the subpath of a
     * version and the subpath doesn't exist.
     */
    @CheckForNull
    private String getReleasedFrozenNodePath(@Nonnull Resource resource, String releasedLabel,
                                             @Nonnull VersionManager versionManager) throws RepositoryException {
        try {
            final VersionHistory versionHistory = versionManager.getVersionHistory(resource.getPath());
            if (versionHistory.hasVersionLabel(releasedLabel)) {
                final Version released = versionHistory.getVersionByLabel(releasedLabel);
                return released.getFrozenNode().getPath();
            }
        } catch (PathNotFoundException | IllegalArgumentException ex) {
            // this exception is thrown if the node is not existing or only exists in the version storage
            LOGGER.debug("No versionhistory for path {} : {}", resource.getPath(), ex);
        }

        final Iterator<Resource> versionHistoryResources = findVersionHistoriesForPathAndRelease(resource.getPath(),
                releasedLabel);
        if (versionHistoryResources.hasNext()) {
            final Resource versionHistoryResource = versionHistoryResources.next();
            if (versionHistoryResources.hasNext()) {
                LOGGER.warn("Unsupported: several versioned nodes with overlapping paths: {}, {}",
                        versionHistoryResource.getPath(), versionHistoryResources.next().getPath());
            }
            String pathToUse = versionHistoryResource.getValueMap().get("default").toString();
            String intermediatePath = resource.getPath().substring(pathToUse.length());
            // take this to find the released version and append the cut off path segments to find the subnode.
            return getReleasedFrozenNodePath(versionHistoryResource, releasedLabel) + intermediatePath;
        }
        return null;
    }

    /**
     * try to find the versionHistory that contains the resources path or any of its parents and has a versionLabel
     * for the release.
     */
    @Nonnull
    protected Iterator<Resource> findVersionHistoriesForPathAndRelease(@Nonnull String path, String releasedLabel) {
        String pathToTest = path + "/";
        StringBuilder condition = new StringBuilder();
        int pos = 0;
        boolean first = true;
        while (0 <= (pos = pathToTest.indexOf('/', pos + 1))) {
            if (!first) condition.append(" or ");
            first = false;
            condition.append(String.format(VERSION_STORAGE_QUERY_CONDITIONTEMPLATE, pathToTest.substring(0, pos)));
        }
        String query = String.format(VERSION_STORAGE_QUERY_TEMPLATE, releasedLabel, condition.toString());
        LOGGER.debug("Find history query: {}", query);
        return resourceResolver.findResources(query, XPATH);
    }

    /**
     * Get the resource of the frozen node tagged with the release tag.
     *
     * @param versionsRootResource the 'root' node of the versionable resource (nt:versionHistory)
     * @return the tagged resource
     */
    public Resource getReleasedFrozenResource(Resource versionsRootResource) throws RepositoryException {
        final String releasedFrozenNodePath = getReleasedFrozenNodePath(versionsRootResource);
        return resourceResolver.getResource(releasedFrozenNodePath);
    }

    @Nonnull
    String getReleasedFrozenNodePath(Resource versionsRootResource) throws RepositoryException {
        final String path = versionsRootResource.getValueMap().get("default", String.class);
        return getReleasedFrozenNodePath(versionsRootResource, this.releasedLabel);
    }

    /** Finds the version for releaseLabel from versionHistory versionHistoryResource . */
    @Nonnull
    protected String getReleasedFrozenNodePath(Resource versionHistoryResource, String releasedLabel)
            throws RepositoryException {
        // try to read the labels for this nodes versions
        final Resource versionLabels = versionHistoryResource.getChild(JCR_VERSIONLABELS);
        if (ResourceUtil.isNonExistingResource(versionLabels)) {
            throw new PathNotFoundException("No labels in " + versionHistoryResource);
        } else {
            // read the uuid of the version, marked with the released label
            final String ref = versionLabels.getValueMap().get(releasedLabel, String.class);
            if (ref == null) {
                // no version is labeled with the released label
                throw new PathNotFoundException("No label " + versionHistoryResource + " release " + releasedLabel);
            } else {
                // find the node, read its path and append the frozen node
                final JackrabbitSession session = (JackrabbitSession) resourceResolver.adaptTo(Session.class);
                final Node taggedVersionNode = session.getNodeByIdentifier(ref);
                return taggedVersionNode.getPath() + "/" + JCR_FROZENNODE;
            }
        }
    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull String absPath) {
        LOGGER.debug("resolve({})", absPath);
        final Resource resource = resourceResolver.resolve(absPath);
        return getReleasedResource(resource);
    }

    @Override
    @Deprecated
    @Nonnull
    public Resource resolve(@Nonnull HttpServletRequest request) {
        LOGGER.debug("resolve({})", request);
        final Resource resource = resourceResolver.resolve(request);
        return getReleasedResource(resource);
    }

    @Override
    @Nonnull
    public String map(@Nonnull String resourcePath) {
        LOGGER.debug("map({})", resourcePath);
        return resourceResolver.map(resourcePath);
    }

    @Override
    @CheckForNull
    public String map(@Nonnull HttpServletRequest request, @Nonnull String resourcePath) {
        LOGGER.debug("map({}, {})", request, resourcePath);
        return resourceResolver.map(request, resourcePath);
    }

    @Override
    @CheckForNull
    public Resource getResource(@Nonnull String path) {
        LOGGER.debug("getResource({})", path);
        Resource resource = resourceResolver.getResource(path);
        if (null == resource) {
            // handle null (resource may exist only in old version)
            resource = new NonExistingResource(this, path);
        }
        resource = getReleasedResource(resource);
        return null == resource || ResourceUtil.isNonExistingResource(resource) ? null : resource;
    }

    @Override
    @CheckForNull
    public Resource getResource(Resource base, @Nonnull String path) {
        LOGGER.debug("getResource({}, {})", base, path);
        if (path.startsWith("/")) return getResource(path);

        Resource resource = resourceResolver.getResource(base, path);
        if (null == resource) {
            // handle null (resource may exist only in old version)
            resource = new NonExistingResource(this, base.getPath() + "/" + path);
        }
        resource = getReleasedResource(resource);
        return null == resource || ResourceUtil.isNonExistingResource(resource) ? null : resource;
    }

    @Override
    @Nonnull
    public String[] getSearchPath() {
        LOGGER.debug("getSearchPath()");
        return resourceResolver.getSearchPath();
    }

    @Override
    public Resource getParent(@Nonnull Resource child) {
        return getReleasedResource(resourceResolver.getParent(Objects.requireNonNull(child)));
    }

    @Override
    @Nonnull
    public Iterator<Resource> listChildren(@Nonnull Resource parent) {
        LOGGER.debug("listChildren({})", parent);
        if (parent instanceof StagingResource) {
            // we should check if ((StagingResource) parent).getFrozenResource() results in a node inside the version storage
            // if not, we should first get the released version of this.
            //       special case: parent (e.g. /content) is not versionable an no parent of parent is versionable
            //       and parent had child nodes in past which are now deleted. so listChildren of the unversioned
            //       node (/content) will not find them. so we have to query the version storage for 'nt:versionHistory' nodes
            //       where the property 'default' starts with parent.getPath() and has one more path level. for all found
            //       resources, we have to find the released versions and merge this into this Iterator if not already there
            // /jcr:root/jcr:system/jcr:versionStorage//*[@jcr:primaryType='nt:versionHistory' and jcr:like(@default, '/content/%')]
            final Resource frozenResource = ((StagingResource) parent).getFrozenResource();
            final Iterator<Resource> iterator;
            if (isInVersionStorage(frozenResource)) {
                iterator = resourceResolver.listChildren(frozenResource);
            } else {
                iterator = resourceResolver.listChildren(getReleasedResource(frozenResource));
                try {
                    if (!isUnderVersionControl(parent)) {
                        // find subnodes of parent in version storage
                        final String parentPath = parent.getPath();
                        final String queryString = String.format(VERSION_STORAGE_LIKE_QUERY, parentPath);
                        final Iterator<Resource> potentialVersionableChildren = resourceResolver.findResources(queryString, XPATH);
                        return new StagingResourceChildrenIterator(iterator, potentialVersionableChildren, this, parentPath);
                    }
                } catch (RepositoryException e) {
                    throw new SlingException("Error acquiring the child resource iterator.", e);
                }
            }
            return new StagingResourceChildrenIterator(iterator, this);
        }
        return resourceResolver.listChildren(parent);
    }

    @Override
    @Nonnull
    public Iterable<Resource> getChildren(@Nonnull Resource parent) {
        LOGGER.debug("getChildren({})", parent);
        if (parent instanceof StagingResource) {
            final Iterator<Resource> iterator = resourceResolver.listChildren(((StagingResource) parent).getFrozenResource());
            return IteratorUtils.asIterable(iterator);
        }
        return resourceResolver.getChildren(parent);
    }

    @Override
    @Nonnull
    public Iterator<Resource> findResources(@Nonnull String query, String language) {
        LOGGER.debug("findResources({}, {})", query, language);
        //TODO not supported?
        //return resourceResolver.findResources(query, language);
        throw new StagingException("findResources not supported / not yet implemented");
    }

    @Override
    @Nonnull
    public Iterator<Map<String, Object>> queryResources(@Nonnull String query, String language) {
        LOGGER.debug("queryResources({}, {})", query, language);
        //TODO not supported?
        //return resourceResolver.queryResources(query, language);
        throw new StagingException("queryResources not supported / not yet implemented");
    }

    @Override
    @CheckReturnValue
    public boolean hasChildren(@Nonnull Resource resource) {
        LOGGER.debug("hasChildren({})", resource);
        // original implementation calls listChildren(..).hasNext()
        // this should work as expected
        return resourceResolver.hasChildren(resource);
    }

    @Override
    @Nonnull
    public ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
        LOGGER.debug("clone({})", authenticationInfo);
        final ResourceResolver resourceResolverC = this.resourceResolverFactory.getResourceResolver(authenticationInfo);
        if (resourceResolverC instanceof StagingResourceResolver) {
            return resourceResolverC;
        } else {
            return new StagingResourceResolver(resourceResolverFactory, resourceResolverC, this.releasedLabel, releaseMapper);
        }
    }

    @Override
    @CheckReturnValue
    public boolean isLive() {
        LOGGER.debug("isLive()");
        return resourceResolver.isLive();
    }

    @Override
    public void close() {
        LOGGER.debug("close()");
        resourceResolver.close();
    }

    @Override
    @CheckForNull
    public String getUserID() {
        LOGGER.debug("getUserID");
        return resourceResolver.getUserID();
    }

    @Override
    @Nonnull
    public Iterator<String> getAttributeNames() {
        LOGGER.debug("getAttributeNames()");
        return resourceResolver.getAttributeNames();
    }

    @Override
    public Object getAttribute(@Nonnull String name) {
        LOGGER.debug("getAttribute({})", name);
        return resourceResolver.getAttribute(name);
    }

    @Override
    public void delete(@Nonnull Resource resource) throws PersistenceException {
        LOGGER.debug("delete({})", resource);
        //resourceResolver.delete(resource);
        throw new StagingException("delete not supported / not yet implemented");
    }

    @Override
    @Nonnull
    public Resource create(@Nonnull Resource parent, @Nonnull String name, Map<String, Object> properties) throws
            PersistenceException {
        LOGGER.debug("create({}, {}, {})", parent, name, properties);
        //return resourceResolver.create(parent, name, properties);
        throw new StagingException("create not supported / not yet implemented");
    }

    @Override
    public void revert() {
        LOGGER.debug("revert()");
        //resourceResolver.revert();
        throw new StagingException("revert not supported / not yet implemented");

    }

    @Override
    public Resource move(String srcAbsPath, String destAbsPath) throws PersistenceException {
        LOGGER.debug("move({}, {})", srcAbsPath, destAbsPath);
        //return resourceResolver.move(srcAbsPath, destAbsPath);
        throw new StagingException("move not supported / not yet implemented");
    }

    @Override
    public Resource copy(String srcAbsPath, String destAbsPath) throws PersistenceException {
        LOGGER.debug("copy({}, {})", srcAbsPath, destAbsPath);
        //return resourceResolver.copy(srcAbsPath, destAbsPath);
        throw new StagingException("copy not supported / not yet implemented");
    }

    @Override
    public void commit() throws PersistenceException {
        LOGGER.debug("commit()");
        //resourceResolver.commit();
        throw new StagingException("commit not supported / not yet implemented");
    }

    @Override
    public boolean hasChanges() {
        LOGGER.debug("hasChanges(): false");
        //TODO not supported?
        //return resourceResolver.hasChanges();
        //throw new RuntimeException("not supported / not yet implemented");
        return false;
    }

    @Override
    public String getParentResourceType(Resource resource) {
        LOGGER.debug("getParentResource({})", resource);
        //TODO do we operate on history versions here?
        return resourceResolver.getParentResourceType(resource);
    }

    @Override
    public String getParentResourceType(String s) {
        LOGGER.debug("getParentResourceType({})", s);
        //TODO do we operate on history versions here?
        return resourceResolver.getParentResourceType(s);
    }

    @Override
    public boolean isResourceType(Resource resource, String s) {
        LOGGER.debug("isResourceType({}, {})", resource, s);
        //TODO do we operate on history versions here?
        return resourceResolver.isResourceType(resource, s);
    }

    @Override
    public void refresh() {
        LOGGER.debug("refresh");
        //TODO not supported or NOOP?
        //resourceResolver.refresh();
        //throw new RuntimeException("not supported / not yet implemented");
    }

    /** Supports creating a {@link QueryBuilder}. */
    // Unfortunately, QueryBuilderAdapterFactory is not sufficient, since this delegates to resourceResolver.
    @Override
    public <AdapterType> AdapterType adaptTo(@Nonnull Class<AdapterType> type) {
        LOGGER.debug("adaptTo({})", type);
        if (QueryBuilder.class.equals(type)) return type.cast(new QueryBuilderImpl(this));
        else if (StagingResourceResolver.class.equals(type)) return type.cast(this);
        return resourceResolver.adaptTo(type);
    }

}

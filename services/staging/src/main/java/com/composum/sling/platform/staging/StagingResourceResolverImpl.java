package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.QueryBuilderImpl;
import com.composum.sling.platform.staging.service.DefaultStagingReleaseManager.ReleaseImpl;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static com.composum.sling.core.util.ResourceUtil.CONTENT_NODE;
import static com.composum.sling.platform.staging.StagingConstants.NODE_RELEASES;

/**
 * <p>A {@link ResourceResolver} that provides transparent access to releases as defined in {@link StagingReleaseManager}.</p>
 * <h3>Limitations:</h3>
 * <ul>
 * <li>This returns read-only resources, and querying is only supported through {@link QueryBuilder}.</li>
 * <li>We also don't support {@link org.apache.sling.resourceresolver.impl.params.ParsedParameters}.</li>
 * <li>We don't include synthetic resources or resources of other resource providers (servlets etc.) into releases.</li>
 * </ul>
 * // FIXME hps 2019-04-05 use service resolver for accessing version space
 */
public class StagingResourceResolverImpl implements ResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResourceResolverImpl.class);

    @Nonnull
    protected final ResourceResolver underlyingResolver;

    @Nonnull
    protected final StagingReleaseManager.Release release;

    @Nonnull
    protected final ReleaseMapper releaseMapper;

    /** ResourceResolverFactory injected into the SlingFilter. Can be used to create new default ResourceResolver. */
    @Nonnull
    private final ResourceResolverFactory resourceResolverFactory;

    public StagingResourceResolverImpl(@Nonnull StagingReleaseManager.Release release, @Nonnull ResourceResolver underlyingResolver, @Nonnull ReleaseMapper releaseMapper, @Nonnull ResourceResolverFactory resourceResolverFactory) {
        this.underlyingResolver = underlyingResolver;
        this.release = release;
        this.releaseMapper = releaseMapper;
        this.resourceResolverFactory = resourceResolverFactory;
    }

    /** The release that is presented by this resolver. */
    @Nonnull
    public StagingReleaseManager.Release getRelease() {
        return release;
    }

    /** The {@link ReleaseMapper} we're applying. */
    @Nonnull
    public ReleaseMapper getReleaseMapper() {
        return releaseMapper;
    }

    /** The resolver we use inside to access the release data. */
    @Nonnull
    public ResourceResolver getUnderlyingResolver() {
        return underlyingResolver;
    }

    /** Checks whether the path is one of the special paths that are overlayed from the workspace. */
    protected boolean isDirectlyMappedPath(String path) {
        return release.appliesToPath(path)
                && path.startsWith(release.getReleaseRoot().getPath() + '/' + CONTENT_NODE);
    }

    /** Checks whether the path is one of the special paths that are not passed through the resolver. */
    protected boolean isFilteredPath(String path) {
        return release.appliesToPath(path)
                && path.startsWith(release.getReleaseRoot().getPath() + '/' + CONTENT_NODE + '/' + NODE_RELEASES + '/');
    }

    /** Returns additional wrapped children overlayed into the release - primarily for {@link #isDirectlyMappedPath(String)}. */
    @Nullable
    protected Iterator<Resource> overlayedChildren(@Nonnull Resource parent) {
        if (release.getReleaseRoot().getPath().equals(parent.getPath())) {
            Resource child = release.getReleaseRoot().getChild(CONTENT_NODE);
            return Collections.singletonList(
                    wrapIntoStagingResource(child.getPath(), child, null, false)
            ).iterator();
        }
        return null;
    }

    /**
     * Finds the simulated resource. The meat of the actual retrieval algorithm.  @param request the request
     *
     * @param rawPath an absolute path (possibly not normalized)
     * @return the resource or a {@link NonExistingResource} if it isn't present somewhere or in the release
     */
    @Nonnull
    protected Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @Nonnull String rawPath) {
        String path = ResourceUtil.normalize(rawPath);
        if (path == null || isFilteredPath(path)) // weird path like /../.. or explicitly removed
            return new NonExistingResource(this, rawPath);
        if (!releaseMapper.releaseMappingAllowed(path) || !release.appliesToPath(path) || isDirectlyMappedPath(path)) {
            // we need to return a StagingResourceImpl, too, since e.g. listChildren might go into releasemapped areas.
            Resource underlyingResource = underlyingResolver.getResource(path);
            return wrapIntoStagingResource(path, underlyingResource, request, true);
        }
        Resource underlyingResource = ReleaseImpl.unwrap(release).getWorkspaceCopyNode();
        if (!release.getReleaseRoot().getPath().equals(path)) {
            if (!path.startsWith(release.getReleaseRoot().getPath() + '/')) // safety check - can't happen.
                throw new IllegalArgumentException("Bug. " + path + " vs. " + release.getReleaseRoot().getPath());
            String relPath = path.substring(release.getReleaseRoot().getPath().length() + 1);
            String[] levels = relPath.split("/");
            for (String level : levels) {
                if (underlyingResource == null) return new NonExistingResource(this, rawPath);
                underlyingResource = underlyingResource.getChild(level);
                underlyingResource = stepResource(underlyingResource);
            }
        }
        return wrapIntoStagingResource(path, underlyingResource, request, true);
    }

    /** Internal-use: Wrap a resource into a {@link StagingResourceImpl} if it exists. */
    public Resource wrapIntoStagingResource(@Nonnull String path, @Nullable Resource underlyingResource, @Nullable HttpServletRequest request, boolean useNonExisting) {
        if (underlyingResource == null)
            return useNonExisting ? new NonExistingResource(this, path) : null;
        if (ResourceUtil.isNonExistingResource(underlyingResource)) return useNonExisting ? underlyingResource : null;
        SlingHttpServletRequest slingRequest = (request instanceof SlingHttpServletRequest) ? (SlingHttpServletRequest) request : null;
        return new StagingResourceImpl(release, path, this, underlyingResource,
                slingRequest != null ? slingRequest.getRequestPathInfo() : null);
    }

    /**
     * Checks whether the resource is exactly on one of the points where we move to a different resource:
     * the release root is actually mapped to the release content root, and a version reference.
     */
    protected Resource stepResource(Resource resource) {
        if (resource == null) {
            return null;
        } else if (resource.getPath().equals(release.getReleaseRoot().getPath())) {
            return ReleaseImpl.unwrap(release).getWorkspaceCopyNode();
        } else if (StagingUtils.isInVersionStorage(resource)) {
            return resource;
        } else if (ResourceHandle.use(resource).isOfType(StagingConstants.TYPE_VERSIONREFERENCE)) {
            Boolean deactivated = resource.getValueMap().get(StagingConstants.PROP_DEACTIVATED, false);
            if (deactivated) return null;
            Resource underlyingResource = null;
            try { // PROP_VERSION is mandatory and version access is needed - no need for checks.
                Resource propertyResource = resource.getChild(StagingConstants.PROP_VERSION);
                underlyingResource = ResourceUtil.getReferredResource(propertyResource);
            } catch (RepositoryException | NullPointerException e) { // weird unexpected case
                // Returning a NonExistingResource here is not good, but breaking everything seems worse.
                LOG.error("Error finding version for " + resource.getPath(), e);
            }
            if (underlyingResource != null)
                underlyingResource = underlyingResource.getChild(JcrConstants.JCR_FROZENNODE);
            return underlyingResource;
        }
        return resource;
    }

    @Override
    @Nonnull
    public Iterator<Resource> listChildren(@Nonnull Resource rawParent) {
        final Resource parent = ResourceUtil.unwrap(rawParent);
        StagingResourceImpl stagingResource = null;
        if (parent instanceof StagingResourceImpl) {
            stagingResource = (StagingResourceImpl) parent;
            if (stagingResource.release.equals(release)) stagingResource = null;
        }
        if (stagingResource == null) {
            Resource retrieved = retrieveReleasedResource(null, parent.getPath());
            if (retrieved instanceof StagingResourceImpl) stagingResource = (StagingResourceImpl) retrieved;
            else return Collections.emptyIterator(); // NonExistingResource
        }
        Iterator<Resource> children = stagingResource.underlyingResource.listChildren();
        Iterator<Resource> resourceIterator = IteratorUtils.filteredIterator(
                IteratorUtils.transformedIterator(children, (r) ->
                        wrapIntoStagingResource(parent.getPath() + "/" + r.getName(), r, null, false)
                ),
                (child) -> child != null && !ResourceUtil.isNonExistingResource(child) && !isFilteredPath(child.getPath())
        );
        Iterator<Resource> additionalChildren = this.overlayedChildren(parent);
        if (additionalChildren != null)
            resourceIterator = IteratorUtils.chainedIterator(additionalChildren, resourceIterator);
        return resourceIterator;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nullable HttpServletRequest request, @Nonnull String rawAbsPath) {
        String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) return new NonExistingResource(this, rawAbsPath);
        Resource resource = request != null ? underlyingResolver.resolve(request, absPath) : underlyingResolver.resolve(absPath);
        if (!releaseMapper.releaseMappingAllowed(rawAbsPath) || !release.appliesToPath(absPath))
            return wrapIntoStagingResource(absPath, resource, request, true);
        return retrieveReleasedResource((SlingHttpServletRequest) request, resource.getPath());
    }

    @Override
    @Nullable
    public Resource getResource(@Nonnull String path) {
        Resource result = null;
        if (path.startsWith("/")) {
            result = retrieveReleasedResource(null, path);
        } else {
            for (final String prefix : underlyingResolver.getSearchPath()) {
                result = getResource(prefix + path);
                if (result != null) break;
            }
        }
        return result != null && ResourceUtil.isNonExistingResource(result) ? null : result;
    }

    @Override
    @Nullable
    public Resource getResource(@Nullable Resource base, @Nonnull String path) {
        String fullPath = path;
        if (!fullPath.startsWith("/") && base != null) {
            base.getPath();
            fullPath = base.getPath() + '/' + fullPath;
        }
        return getResource(fullPath);
    }

    // ------------------------- Start of the easy parts
    // unsupported modification methods and simply forwarded to underlyingResolver
    // or can just be implemented in terms of other methods.

    @Override
    @Nullable
    public String getParentResourceType(@Nullable Resource resource) {
        return underlyingResolver.getParentResourceType(resource);
    }

    @Override
    @Nullable
    public String getParentResourceType(@Nullable String resourceType) {
        return underlyingResolver.getParentResourceType(resourceType);
    }

    @Override
    public boolean isResourceType(@Nullable Resource resource, @Nullable String resourceType) {
        return underlyingResolver.isResourceType(resource, resourceType);
    }

    @Override
    @Nonnull
    public Iterable<Resource> getChildren(@Nonnull Resource parent) {
        return () -> listChildren(parent);
    }

    @Override
    public boolean hasChildren(@Nonnull Resource resource) {
        return listChildren(resource).hasNext();
    }

    @Override
    @Nullable
    public Resource getParent(@Nonnull Resource child) {
        String parent = ResourceUtil.getParent(child.getPath());
        return parent != null ? getResource(parent) : null;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull String absPath) {
        return resolve(null, absPath);
    }

    @Override
    @Nonnull
    @Deprecated
    public Resource resolve(@Nonnull HttpServletRequest request) {
        return resolve(request, request.getPathInfo());
    }

    @Override
    @Nonnull
    public String map(@Nonnull String resourcePath) {
        return underlyingResolver.map(resourcePath);
    }

    @Override
    @Nullable
    public String map(@Nonnull HttpServletRequest request, @Nonnull String resourcePath) {
        return underlyingResolver.map(request, resourcePath);
    }

    @Override
    @Nonnull
    public String[] getSearchPath() {
        return underlyingResolver.getSearchPath();
    }

    /**
     * Not implemented, since that'd require parsing and rewriting the query - please use {@link com.composum.sling.platform.staging.query.QueryBuilder}.
     *
     * @see com.composum.sling.platform.staging.query.QueryBuilder
     */
    @Override
    @Nonnull
    public Iterator<Resource> findResources(@Nonnull String query, @Nullable String language) {
        throw new UnsupportedOperationException("findResources not supported / not yet implemented. Please use QueryBuilder.");
    }

    /**
     * Not implemented, since that'd require parsing and rewriting the query - please use {@link com.composum.sling.platform.staging.query.QueryBuilder}.
     *
     * @see com.composum.sling.platform.staging.query.QueryBuilder
     */
    @Override
    @Nonnull
    public Iterator<Map<String, Object>> queryResources(@Nonnull String query, @Nullable String language) {
        throw new UnsupportedOperationException("queryResources not supported / not yet implemented. Please use QueryBuilder.");
    }

    @Override
    @Nonnull
    public ResourceResolver clone(@Nullable Map<String, Object> authenticationInfo) throws LoginException {
        ResourceResolver resolver = underlyingResolver.clone(authenticationInfo);
        if (resolver instanceof StagingResourceResolverImpl)
            return resolver;
        else
            return new StagingResourceResolverImpl(release, resolver, releaseMapper, resourceResolverFactory);
    }

    @Override
    public boolean isLive() {
        return underlyingResolver.isLive();
    }

    @Override
    public void close() {
        underlyingResolver.close();
    }

    @Override
    @Nullable
    public String getUserID() {
        return underlyingResolver.getUserID();
    }

    @Override
    @Nonnull
    public Iterator<String> getAttributeNames() {
        return underlyingResolver.getAttributeNames();
    }

    @Override
    @Nullable
    public Object getAttribute(@Nonnull String name) {
        return underlyingResolver.getAttribute(name);
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    @Nonnull
    public Resource create(@Nonnull Resource parent, @Nonnull String name, @Nullable Map<String, Object> properties) throws PersistenceException {
        throw new PersistenceException("creating resources not implemented - readonly view.");
    }


    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void delete(@Nonnull Resource resource) throws PersistenceException {
        throw new PersistenceException("deleting resources not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void revert() {
        throw new UnsupportedOperationException("revert not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void commit() throws PersistenceException {
        throw new PersistenceException("commit not implemented - readonly view.");
    }

    /** Always false since this provides an readonly view. */
    @Override
    public boolean hasChanges() {
        return false;
    }

    @Override
    public void refresh() {
        underlyingResolver.refresh();
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    @Nullable
    public Resource copy(@Nullable String srcAbsPath, @Nullable String destAbsPath) throws PersistenceException {
        throw new PersistenceException("copy not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    @Nullable
    public Resource move(@Nullable String srcAbsPath, @Nullable String destAbsPath) throws PersistenceException {
        throw new PersistenceException("move not implemented - readonly view.");
    }

    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(@Nonnull Class<AdapterType> type) {
        if (QueryBuilder.class.equals(type))
            return type.cast(new QueryBuilderImpl(this));
        else if (StagingResourceResolverImpl.class.isAssignableFrom(type))
            return type.cast(this);
        return underlyingResolver.adaptTo(type);
    }

    // ------------------ End of the easy parts.
}

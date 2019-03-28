package com.composum.sling.platform.staging;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.QueryBuilderImpl;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.Map;

/**
 * <p>A {@link ResourceResolver} that provides transparent access to releases as defined in {@link StagingReleaseManager}.</p>
 * <h3>Limitations:</h3>
 * <ul>
 * <li>This returns read-only resources, and querying is only supported through {@link QueryBuilder}.</li>
 * <li>We also don't support {@link org.apache.sling.resourceresolver.impl.params.ParsedParameters}.</li>
 * <li>We don't include synthetic resources or resources of other resource providers (servlets etc.) into releases.</li>
 * </ul>
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

    protected StagingResourceResolverImpl(@Nonnull StagingReleaseManager.Release release, @Nonnull ResourceResolver underlyingResolver, @Nonnull ReleaseMapper releaseMapper, @Nonnull ResourceResolverFactory resourceResolverFactory) {
        this.underlyingResolver = underlyingResolver;
        this.release = release;
        this.releaseMapper = releaseMapper;
        this.resourceResolverFactory = resourceResolverFactory;
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
        if (path == null) return new NonExistingResource(this, rawPath); // weird path like /../..
        if (!releaseMapper.releaseMappingAllowed(path) || !release.appliesToPath(path)) {
            return underlyingResolver.resolve(path);
        }
        Resource releaseWorkspaceCopy = release.getReleaseNode().getChild(StagingConstants.NODE_RELEASE_ROOT);
        Resource underlyingResource;
        if (release.getReleaseRoot().getPath().equals(path)) {
            underlyingResource = releaseWorkspaceCopy;
        } else {
            if (!path.startsWith(release.getReleaseRoot().getPath() + '/')) // safety check
                throw new IllegalStateException("Bug. " + path);
            String relPath = path.substring(release.getReleaseRoot().getPath().length()); // starts with /
            String pathIntoWorkspaceCopy = releaseWorkspaceCopy.getPath() + relPath;
            underlyingResource = underlyingResolver.resolve(pathIntoWorkspaceCopy);
            StringBuilder restPath = new StringBuilder();
            while (underlyingResource != null && ResourceUtil.isNonExistingResource(underlyingResource)) {
                restPath.insert(0, underlyingResource.getName());
                restPath.insert(0, '/');
                underlyingResource = underlyingResource.getParent();
            }
            if (StagingUtils.isVersionReference(underlyingResource)) {
                String versionUuid = underlyingResource.getValueMap().get(StagingConstants.PROP_VERSION, String.class);
                try {
                    underlyingResource = ResourceUtil.getByUuid(underlyingResolver, versionUuid);
                } catch (RepositoryException e) { // weird unexpected case
                    // Returning a NonExistingResource here is not good, but breaking everything seems worse.
                    underlyingResource = null;
                    LOG.error("Error finding version for " + underlyingResource.getPath(), e);
                }
                if (underlyingResource != null)
                    underlyingResource = underlyingResource.getChild(JcrConstants.JCR_FROZENNODE + restPath);
            }
            if (underlyingResource == null) // version disabled or no corresponding frozen node found
                return new NonExistingResource(this, rawPath);
        }
        return new StagingResourceImpl(release, path, this, underlyingResource,
                request != null ? request.getRequestPathInfo() : null);
    }

    @Override
    @Nonnull
    public Iterator<Resource> listChildren(@Nonnull Resource parent) {
        LOG.error("StagingResourceResolverImpl.listChildren");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.listChildren");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.listChildren
        @Nonnull Iterator<Resource> result = null;
        return result;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nullable HttpServletRequest request, @Nonnull String rawAbsPath) {
        String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) return new NonExistingResource(this, rawAbsPath);
        Resource resource = request != null ? underlyingResolver.resolve(request, absPath) : underlyingResolver.resolve(absPath);
        if (!releaseMapper.releaseMappingAllowed(rawAbsPath) || !release.appliesToPath(absPath))
            return resource;
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
        throw new UnsupportedOperationException("creating resources not implemented - readonly view.");
    }


    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void delete(@Nonnull Resource resource) throws PersistenceException {
        throw new UnsupportedOperationException("deleting resources not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void revert() {
        throw new UnsupportedOperationException("revert not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public void commit() throws PersistenceException {
        throw new UnsupportedOperationException("commit not implemented - readonly view.");
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
        throw new UnsupportedOperationException("copy not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    @Nullable
    public Resource move(@Nullable String srcAbsPath, @Nullable String destAbsPath) throws PersistenceException {
        throw new UnsupportedOperationException("move not implemented - readonly view.");
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

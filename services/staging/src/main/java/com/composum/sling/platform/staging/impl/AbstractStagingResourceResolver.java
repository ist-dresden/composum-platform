package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.PropertyUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Common methods for resource resolvers that present data from previous versions stored in the JCR content
 * as normal resources.
 */
public abstract class AbstractStagingResourceResolver implements ResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractStagingResourceResolver.class);
    @Nonnull
    protected final ResourceResolver underlyingResolver;
    protected final boolean closeResolverOnClose;

    public AbstractStagingResourceResolver(@Nonnull ResourceResolver underlyingResolver, boolean closeResolverOnClose) {
        this.underlyingResolver = Objects.requireNonNull(underlyingResolver);
        this.closeResolverOnClose = closeResolverOnClose;
    }

    /**
     * Finds the simulated resource. The meat of the actual retrieval algorithm.
     *
     * @param request the request
     * @param rawPath an absolute path (possibly not normalized)
     * @return the (possibly simulated) resource or a {@link NonExistingResource} if it isn't present
     */
    @Nonnull
    protected abstract Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @Nonnull String rawPath);


    /**
     * Internal-use: Wrap a resource into a {@link StagingResource} if it exists.
     *
     * @deprecated for staging-internal use only
     */
    @Deprecated
    public Resource wrapIntoStagingResource(@Nonnull String path, @Nullable Resource underlyingResource, @Nullable HttpServletRequest request, boolean useNonExisting) {
        if (underlyingResource == null)
            return useNonExisting ? new NonExistingResource(this, path) : null;

        if (ResourceUtil.isNonExistingResource(underlyingResource)) return useNonExisting ? underlyingResource : null;

        SlingHttpServletRequest slingRequest = (request instanceof SlingHttpServletRequest) ? (SlingHttpServletRequest) request : null;
        return new StagingResource(path, this, underlyingResource,
                slingRequest != null ? slingRequest.getRequestPathInfo() : null);
    }

    /** Checks whether the resource is a versionable for which we step into version space. */
    protected abstract Resource stepResource(Resource resource);

    /** Returns additional wrapped children overlayed to the children of the underlying resource. */
    @Nullable
    protected Iterator<Resource> overlayedChildren(@Nonnull Resource parent) {
        return null;
    }

    /** Returns true if the resource should not be forwarded outside. */
    protected boolean isFiltered(Resource resource) {
        return false;
    }

    @Override
    @Nonnull
    public Iterator<Resource> listChildren(@Nonnull Resource rawParent) {
        final Resource parent = ResourceUtil.unwrap(rawParent);
        StagingResource stagingResource = null;
        if (parent instanceof StagingResource && parent.getResourceResolver() == this) {
            stagingResource = (StagingResource) parent;
        }
        if (stagingResource == null) {
            Resource retrieved = retrieveReleasedResource(null, parent.getPath());
            if (retrieved instanceof StagingResource) stagingResource = (StagingResource) retrieved;
            else return Collections.emptyIterator(); // NonExistingResource
        }
        Iterator<Resource> children = stagingResource.underlyingResource.listChildren();
        Iterator<Resource> resourceIterator = IteratorUtils.filteredIterator(
                IteratorUtils.transformedIterator(children, (r) ->
                        wrapIntoStagingResource(parent.getPath() + "/" + r.getName(), stepResource(r), null, false)
                ),
                (child) -> child != null && !ResourceUtil.isNonExistingResource(child) && !isFiltered(child)
        );
        Iterator<Resource> additionalChildren = this.overlayedChildren(parent);
        if (additionalChildren != null)
            resourceIterator = IteratorUtils.chainedIterator(additionalChildren, resourceIterator);
        return resourceIterator;
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

    /**
     * {@inheritDoc}
     * Additionally, we support adapting to the specific type of this resolver, to easily pierce through
     * {@link org.apache.sling.api.wrappers.ResourceResolverWrapper}s.
     */
    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(@Nullable Class<AdapterType> type) {
        if (type.isAssignableFrom(this.getClass()))
            return type.cast(this);
        return underlyingResolver.adaptTo(type);
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
    public boolean isLive() {
        return underlyingResolver.isLive();
    }

    @Override
    public void close() {
        if (closeResolverOnClose)
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

    /** Not implemented, since this resolver provides an readonly view of things. */
    @Override
    @Nullable
    public Resource copy(@Nullable String srcAbsPath, @Nullable String destAbsPath) throws PersistenceException {
        throw new PersistenceException("copy not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readonly view of things. */
    @Override
    @Nullable
    public Resource move(@Nullable String srcAbsPath, @Nullable String destAbsPath) throws PersistenceException {
        throw new PersistenceException("move not implemented - readonly view.");
    }

    // ------------------ End of the easy parts.

}

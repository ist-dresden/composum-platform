package com.composum.sling.platform.staging;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.QueryBuilderImpl;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import org.apache.sling.api.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.Map;

class StagingResourceResolverImpl implements ResourceResolver {

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

    @Override
    @Nonnull
    public Resource resolve(@Nonnull HttpServletRequest request, @Nonnull String absPath) {
        LOG.error("StagingResourceResolverImpl.resolve");
        if (0 == 0) throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.resolve");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.resolve
        @Nonnull Resource result = null;
        return result;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull String absPath) {
        LOG.error("StagingResourceResolverImpl.resolve");
        if (0 == 0) throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.resolve");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.resolve
        Resource result = null;
        return result;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull HttpServletRequest request) {
        LOG.error("StagingResourceResolverImpl.resolve");
        if (0 == 0) throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.resolve");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.resolve
        Resource result = null;
        return result;
    }

    @Override
    @Nullable
    public Resource getResource(@Nonnull String path) {
        LOG.error("StagingResourceResolverImpl.getResource");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.getResource");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.getResource
        Resource result = null;
        return result;
    }

    @Override
    @Nullable
    public Resource getResource(Resource base, @Nonnull String path) {
        LOG.error("StagingResourceResolverImpl.getResource");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.getResource");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.getResource
        Resource result = null;
        return result;
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
    @Nullable
    public Resource getParent(@Nonnull Resource child) {
        return getResource(ResourceUtil.getParent(child.getPath()));
    }

    @Override
    @Nonnull
    public Iterable<Resource> getChildren(@Nonnull Resource parent) {
        LOG.error("StagingResourceResolverImpl.getChildren");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.getChildren");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.getChildren
        Iterable<Resource> result = null;
        return result;
    }

    @Override
    public boolean hasChildren(@Nonnull Resource resource) {
        LOG.error("StagingResourceResolverImpl.hasChildren");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.hasChildren");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.hasChildren
        boolean result = false;
        return result;
    }


    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    @Nonnull
    public Resource create(@Nonnull Resource parent, @Nonnull String name, Map<String, Object> properties) throws PersistenceException {
        LOG.error("StagingResourceResolverImpl.create");
        if (0 == 0) throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.create");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.create
        Resource result = null;
        return result;
    }

    @Override
    @Nullable
    public String getParentResourceType(Resource resource) {
        LOG.error("StagingResourceResolverImpl.getParentResourceType");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.getParentResourceType");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.getParentResourceType
        String result = null;
        return result;
    }

    @Override
    @Nullable
    public String getParentResourceType(String resourceType) {
        LOG.error("StagingResourceResolverImpl.getParentResourceType");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.getParentResourceType");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.getParentResourceType
        String result = null;
        return result;
    }

    @Override
    public boolean isResourceType(Resource resource, String resourceType) {
        LOG.error("StagingResourceResolverImpl.isResourceType");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: StagingResourceResolverImpl.isResourceType");
        // FIXME hps 2019-03-27 implement StagingResourceResolverImpl.isResourceType
        boolean result = false;
        return result;
    }

    // ------------------------- Start of the easy parts
    // unsupported modification methods and simply forwarded to underlyingResolver
    // or can just be implemented in terms of other methods.

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
    public Iterator<Resource> findResources(@Nonnull String query, String language) {
        throw new UnsupportedOperationException("findResources not supported / not yet implemented. Please use QueryBuilder.");
    }

    /**
     * Not implemented, since that'd require parsing and rewriting the query - please use {@link com.composum.sling.platform.staging.query.QueryBuilder}.
     *
     * @see com.composum.sling.platform.staging.query.QueryBuilder
     */
    @Override
    @Nonnull
    public Iterator<Map<String, Object>> queryResources(@Nonnull String query, String language) {
        throw new UnsupportedOperationException("queryResources not supported / not yet implemented. Please use QueryBuilder.");
    }

    @Override
    @Nonnull
    public ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
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
    public Resource copy(String srcAbsPath, String destAbsPath) throws PersistenceException {
        throw new UnsupportedOperationException("copy not implemented - readonly view.");
    }

    /** Not implemented, since this resolver provides an readon view of things. */
    @Override
    public Resource move(String srcAbsPath, String destAbsPath) throws PersistenceException {
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

package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.service.StagingReleaseManager;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Simulates a {@link org.apache.sling.api.resource.Resource}s from a release. It can either be a (writable) real resource,
 * a (read only) resource from the working tree of the release, or a wrapped frozen node from version storage.
 */
class StagingResourceImpl extends AbstractResource {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResourceImpl.class);

    @Nonnull
    protected final StagingReleaseManager.Release release;

    /** The simulated path - might be different from the real path. */
    @Nonnull
    protected final String path;

    /** StagingResourceResolverImpl */
    @Nonnull
    protected final ResourceResolver resolver;

    @Nonnull
    protected final Resource underlyingResource;

    @CheckForNull
    protected final RequestPathInfo pathInfo;

    /**
     * Instantiates a new Staging resource.
     *
     * @param release            the release this applies to
     * @param path               the simulated path
     * @param resolver           the {@link StagingResourceResolverImpl} resolver
     * @param underlyingResource the underlying resource
     * @param pathInfo           the path info from the request if the resource wraps a request resource
     */
    StagingResourceImpl(@Nonnull StagingReleaseManager.Release release, @Nonnull String path, @Nonnull ResourceResolver resolver, @Nonnull Resource underlyingResource, @Nullable RequestPathInfo pathInfo) {
        this.release = release;
        this.path = path;
        this.resolver = resolver;
        this.underlyingResource = underlyingResource;
        this.pathInfo = pathInfo;
    }

    @Override
    @Nonnull
    public String getPath() {
        return path;
    }

    @Override
    @Nonnull
    public String getResourceType() {
        LOG.error("StagingResourceImpl.getResourceType");
        if (0 == 0) throw new UnsupportedOperationException("Not implemented yet: StagingResourceImpl.getResourceType");
        // FIXME hps 2019-03-27 implement StagingResourceImpl.getResourceType
        String result = null;
        return result;
    }

    @Override
    @Nullable
    public String getResourceSuperType() {
        return underlyingResource.getResourceSuperType();
    }

    @Override
    @Nonnull
    public ResourceMetadata getResourceMetadata() {
        final ResourceMetadata resourceMetadata;
        if (pathInfo != null && pathInfo.getResourcePath().equals(path)) {
            resourceMetadata = new StagingResourceMetadata(
                    underlyingResource.getResourceMetadata(),
                    pathInfo.getResourcePath(),
                    pathInfo.getExtension());
        } else {
            resourceMetadata = new StagingResourceMetadata(
                    underlyingResource.getResourceMetadata(),
                    path,
                    null);
        }
        resourceMetadata.lock();
        return resourceMetadata;
    }

    @Override
    @Nonnull
    public ResourceResolver getResourceResolver() {
        return resolver;
    }

    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == null) return null;
        if (ModifiableValueMap.class.isAssignableFrom(type))
            return null; // we currently don't support any modification.
        if (ValueMap.class.isAssignableFrom(type))
            return type.cast(getValueMap());
        return super.adaptTo(type);
    }
}

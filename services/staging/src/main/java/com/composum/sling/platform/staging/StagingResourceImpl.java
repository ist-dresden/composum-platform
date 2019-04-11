package com.composum.sling.platform.staging;

import com.composum.sling.core.JcrResource;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.wrappers.DeepReadValueMapDecorator;
import org.apache.sling.jcr.resource.api.JcrResourceConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.Property;

/**
 * Simulates a {@link org.apache.sling.api.resource.Resource}s from a release. It can either be a (writable) real resource,
 * a (read only) resource from the working tree of the release, or a wrapped frozen node from version storage.
 */
class StagingResourceImpl extends AbstractResource implements JcrResource {

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
        if (underlyingResource.isResourceType(StagingConstants.TYPE_VERSIONREFERENCE) ||
                underlyingResource.isResourceType(StagingConstants.TYPE_MIX_RELEASE_ROOT)) {
            // safety check - these are boundaries where the resolver should have switched to another resource.
            throw new IllegalArgumentException("Bug: underlying resource is " + underlyingResource.getPath());
        }
    }

    @Override
    @Nonnull
    public String getPath() {
        return path;
    }

    @Override
    @Nonnull
    public String getResourceType() {
        if (StagingUtils.isPropertyResource(underlyingResource)) {
            return getParent().getResourceType() + "/" + getName();
        }
        ValueMap vm = getValueMap();
        String result = vm.get(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY, String.class);
        if (StringUtils.isBlank(result)) {
            result = vm.get(JcrConstants.JCR_PRIMARYTYPE, String.class);
        }
        return StringUtils.defaultIfBlank(result, Resource.RESOURCE_TYPE_NON_EXISTING);
    }

    @Override
    public String getPrimaryType() {
        return getValueMap().get(JcrConstants.JCR_PRIMARYTYPE, String.class);
    }

    @Override
    public String getName() {
        if (StagingUtils.isPropertyResource(underlyingResource) && StagingUtils.isInVersionStorage(underlyingResource)) {
            String name = underlyingResource.getName();
            return StagingConstants.FROZEN_PROP_NAMES_TO_REAL_NAMES.getOrDefault(name, name);
        }
        return ResourceUtil.getName(path);
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

    @Nonnull
    @Override
    public ValueMap getValueMap() {
        ValueMap valueMap = StagingUtils.isInVersionStorage(underlyingResource) ?
                new StagingResourceValueMap(underlyingResource.getValueMap()) : underlyingResource.getValueMap();
        return new DeepReadValueMapDecorator(this, valueMap);
    }

    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(@Nullable Class<AdapterType> type) {
        if (type == null) return null;
        if (ModifiableValueMap.class.isAssignableFrom(type)) { // FIXME hps 2019-04-10 overlayed nodes are modifiable
            if (release.appliesToPath(path) || StagingUtils.isInVersionStorage(underlyingResource))
                return null; // unmodifiable
            return type.cast(underlyingResource.adaptTo(ModifiableValueMap.class));
            // a bit dangerous because of relative paths, but we need this for metadata etc.
        }
        // we currently only have r/o support - even outside the release tree, since it'd otherwise be a bit difficult to get listChildren right
        // that can be extended if neccesary.
        if (Node.class.isAssignableFrom(type)) {
            Node node = underlyingResource.adaptTo(Node.class);
            return type.cast(UnmodifiableNodeWrapper.wrap(node, this));
        }
        if (Property.class.isAssignableFrom(type)) {
            Property property = underlyingResource.adaptTo(Property.class);
            return type.cast(UnmodifiablePropertyWrapper.wrap(property, this.path));
        }
        if (ValueMap.class.isAssignableFrom(type))
            return type.cast(getValueMap());
        if (javax.jcr.Session.class.isAssignableFrom(type)) {
            LOG.warn("adaptTo(Session) called on Staged Resource - using the session might create wrong results.");
        }
        return super.adaptTo(type);
    }

    @Override
    @Nonnull
    public String toString() {
        final StringBuilder sb = new StringBuilder("StagingResourceImpl{");
        sb.append("release=").append(release);
        sb.append(", path='").append(path).append('\'');
        sb.append(", underlying='").append(underlyingResource.getPath()).append('\'');
        if (pathInfo != null) sb.append(", pathInfo=").append(pathInfo);
        sb.append('}');
        return sb.toString();
    }

}

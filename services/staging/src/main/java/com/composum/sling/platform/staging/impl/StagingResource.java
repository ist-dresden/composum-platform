package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.JcrResource;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.AbstractResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.DeepReadValueMapDecorator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.jcr.Node;
import javax.jcr.Property;
import java.io.InputStream;

import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;

/**
 * Simulates a {@link org.apache.sling.api.resource.Resource}s from a release. It can either be a (writable) real resource,
 * a (read only) resource from the working tree of the release, or a wrapped frozen node from version storage.
 */
public class StagingResource extends AbstractResource implements JcrResource {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResource.class);

    /** The simulated path - might be different from the real path. */
    @NotNull
    protected final String path;

    @NotNull
    protected final AbstractStagingResourceResolver resolver;

    @NotNull
    protected final Resource underlyingResource;

    @CheckForNull
    protected final RequestPathInfo pathInfo;

    /**
     * Instantiates a new Staging resource.
     *
     * @param path               the simulated path
     * @param resolver           the {@link StagingResourceResolver} resolver
     * @param underlyingResource the underlying resource
     * @param pathInfo           the path info from the request if the resource wraps a request resource
     */
    protected StagingResource(@NotNull String path, @NotNull AbstractStagingResourceResolver resolver, @NotNull Resource underlyingResource, @Nullable RequestPathInfo pathInfo) {
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
    @NotNull
    public String getPath() {
        return path;
    }

    @Override
    @NotNull
    public String getResourceType() {
        if (StagingUtils.isPropertyResource(underlyingResource)) {
            return getParent().getResourceType() + "/" + getName();
        }
        ValueMap vm = getValueMap();
        String result = vm.get(ResourceUtil.PROP_RESOURCE_TYPE, String.class);
        if (StringUtils.isBlank(result)) {
            result = vm.get(JCR_FROZENPRIMARYTYPE, String.class);
            if (StringUtils.isBlank(result)) { result = vm.get(JcrConstants.JCR_PRIMARYTYPE, String.class); }
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
    @NotNull
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
    @NotNull
    public ResourceResolver getResourceResolver() {
        return resolver;
    }

    @NotNull
    @Override
    public ValueMap getValueMap() {
        ValueMap valueMap = StagingUtils.isInStorage(underlyingResource) ?
                new StagingResourceValueMap(underlyingResource) : underlyingResource.getValueMap();
        return new DeepReadValueMapDecorator(this, valueMap);
    }

    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(@Nullable Class<AdapterType> type) {
        if (type == null) { return null; }
        // we currently only have r/o support - even outside the release tree. that can be extended if neccesary.
        if (Node.class.isAssignableFrom(type)) {
            Node node = underlyingResource.adaptTo(Node.class);
            return type.cast(FrozenNodeWrapper.wrap(node, this));
        }
        if (Property.class.isAssignableFrom(type)) {
            Property property = underlyingResource.adaptTo(Property.class);
            return type.cast(FrozenPropertyWrapper.wrap(property, this.path));
        }
        if (ValueMap.class.isAssignableFrom(type)) { return type.cast(getValueMap()); }
        if (InputStream.class.isAssignableFrom(type)) {
            return type.cast(underlyingResource.adaptTo(type));
        }
        if (javax.jcr.Session.class.isAssignableFrom(type)) {
            LOG.warn("adaptTo(Session) called on Staged Resource - using the session might create wrong results.");
        }
        return super.adaptTo(type);
    }

    @Override
    @NotNull
    public String toString() {
        final StringBuilder sb = new StringBuilder("StagingResource{");
        sb.append("path='").append(path).append('\'');
        sb.append(", underlying='").append(underlyingResource.getPath()).append('\'');
        if (pathInfo != null) { sb.append(", pathInfo=").append(pathInfo); }
        sb.append('}');
        return sb.toString();
    }

}

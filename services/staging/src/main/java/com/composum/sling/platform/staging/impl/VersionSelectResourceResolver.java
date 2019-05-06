package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
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

/**
 * A unmodifiable {@link org.apache.sling.api.resource.ResourceResolver} for which one can specify specific versions of
 * {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE} which are presented as if that was the
 * current content. (Unmodifiable in the sense that no content can be modified through it.
 */
public class VersionSelectResourceResolver extends AbstractStagingResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VersionSelectResourceResolver.class);

    public VersionSelectResourceResolver(@Nonnull ResourceResolver underlyingResolver, boolean closeResolverOnClose) {
        super(underlyingResolver, closeResolverOnClose);
    }

    @Nonnull
    @Override
    protected Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @Nonnull String rawPath) {
        LOG.error("VersionSelectResourceResolver.retrieveReleasedResource");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: VersionSelectResourceResolver.retrieveReleasedResource");
        // FIXME hps 2019-05-06 implement VersionSelectResourceResolver.retrieveReleasedResource
        Resource result = null;
        return result;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull HttpServletRequest request, @Nonnull String absPath) {
        LOG.error("VersionSelectResourceResolver.resolve");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: VersionSelectResourceResolver.resolve");
        // FIXME hps 2019-05-06 implement VersionSelectResourceResolver.resolve
        Resource result = null;
        return result;
    }

    @Nonnull
    @Override
    public Iterator<Resource> listChildren(@Nonnull Resource rawParent) {
        final Resource parent = ResourceUtil.unwrap(rawParent);
        StagingResource stagingResource = null;
        if (parent instanceof StagingResource) {
            stagingResource = (StagingResource) parent;
        }
        if (stagingResource == null) {
            Resource retrieved = retrieveReleasedResource(null, parent.getPath());
            if (retrieved instanceof StagingResource) stagingResource = (StagingResource) retrieved;
            else return Collections.emptyIterator(); // NonExistingResource
        }
        Iterator<Resource> children = stagingResource.underlyingResource.listChildren();
//        Iterator<Resource> resourceIterator = IteratorUtils.filteredIterator(
//                IteratorUtils.transformedIterator(children, (r) ->
//                        wrapIntoStagingResource(parent.getPath() + "/" + r.getName(), stepResource(r), null, false)
//                ),
//                (child) -> child != null && !ResourceUtil.isNonExistingResource(child) && !isFilteredPath(child.getPath())
//        );
//        Iterator<Resource> additionalChildren = this.overlayedChildren(parent);
//        if (additionalChildren != null)
//            resourceIterator = IteratorUtils.chainedIterator(additionalChildren, resourceIterator);
//        return resourceIterator;
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-05-06 not implemented
    }

    @Override
    public @Nonnull
    ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
        ResourceResolver resolver = underlyingResolver.clone(authenticationInfo);
        return new VersionSelectResourceResolver(underlyingResolver, closeResolverOnClose);
    }

}

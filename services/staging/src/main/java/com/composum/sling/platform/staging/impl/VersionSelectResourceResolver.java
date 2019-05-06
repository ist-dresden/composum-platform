package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.Validate;
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

import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;
import static org.apache.jackrabbit.JcrConstants.*;

/**
 * A unmodifiable {@link org.apache.sling.api.resource.ResourceResolver} for which one can specify specific versions of
 * {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE} which are presented as if that was the
 * current content. (Unmodifiable in the sense that no content can be modified through it.
 */
public class VersionSelectResourceResolver extends AbstractStagingResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VersionSelectResourceResolver.class);

    @Nonnull
    private final Map<String, String> historyToVersion;

    /**
     * Creates a {@link VersionSelectResourceResolver} replacing a number of versionables with historical versions.
     *
     * @param underlyingResolver   the resolver we use to retrieve things
     * @param historyToVersion     a map that maps the {@value org.apache.jackrabbit.JcrConstants#JCR_VERSIONHISTORY} of all the
     *                             {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE}s for which we want to simulate historical versions to the
     *                             {@value org.apache.jackrabbit.JcrConstants#JCR_BASEVERSION} that needs to be simulated
     * @param closeResolverOnClose if true, the {underlyingResolver} will be closed as well if this resolver is closed
     */
    public VersionSelectResourceResolver(@Nonnull ResourceResolver underlyingResolver,
                                         @Nonnull Map<String, String> historyToVersion, boolean closeResolverOnClose) {
        super(underlyingResolver, closeResolverOnClose);
        this.historyToVersion = historyToVersion;
    }

    /**
     * Creates a {@link VersionSelectResourceResolver} replacing one versionables with a historical version.
     *
     * @param underlyingResolver   the resolver we use to retrieve things
     * @param versionHistoryUuid   the {@value org.apache.jackrabbit.JcrConstants#JCR_VERSIONHISTORY} the replaced
     *                             {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE}
     * @param versionUuid          the {@value org.apache.jackrabbit.JcrConstants#JCR_BASEVERSION} of the replaced
     *                             {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE}s
     * @param closeResolverOnClose if true, the {underlyingResolver} will be closed as well if this resolver is closed
     */
    public VersionSelectResourceResolver(@Nonnull ResourceResolver underlyingResolver,
                                         @Nonnull String versionHistoryUuid, @Nonnull String versionUuid,
                                         boolean closeResolverOnClose) {
        this(underlyingResolver, Collections.singletonMap(versionHistoryUuid, versionUuid), closeResolverOnClose);
    }


    @Nonnull
    @Override
    protected Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @Nonnull String rawPath) {
        Validate.isTrue(rawPath.startsWith("/"), "Absolute path required, but got %s", rawPath);
        String path = ResourceUtil.normalize(rawPath);
        if (path == null) // weird path like /../.. or explicitly removed
            return new NonExistingResource(this, rawPath);

        if (StagingUtils.isInVersionStorage(path)) {
            Resource underlyingResource = underlyingResolver.getResource(path);
            return wrapIntoStagingResource(path, underlyingResource, request, true);
        }

        Resource underlyingResource = underlyingResolver.getResource("/");
        String[] levels = path.replaceFirst("^/", "").split("/");
        for (String level : levels) {
            if (underlyingResource == null) return new NonExistingResource(this, path);
            String actualname = StagingUtils.isInVersionStorage(underlyingResource) ?
                    REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(level, level) // mapping for property resources
                    : level;
            underlyingResource = underlyingResource.getChild(actualname);
            underlyingResource = stepResource(underlyingResource);
        }
        return wrapIntoStagingResource(path, underlyingResource, request, true);

    }

    @Override
    @Nonnull
    public Resource resolve(@Nonnull HttpServletRequest request, @Nonnull String rawAbsPath) {
        String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) return new NonExistingResource(this, rawAbsPath);
        Resource resource = request != null ? underlyingResolver.resolve(request, absPath) : underlyingResolver.resolve(absPath);
        return retrieveReleasedResource((SlingHttpServletRequest) request, resource.getPath());
    }

    /** Checks whether the resource is a versionable for which we step into version space. */
    @Override
    protected Resource stepResource(Resource resource) {
        if (ResourceUtil.isNodeType(resource, MIX_VERSIONABLE)) {
            ValueMap vm = resource.getValueMap();
            String versionHistoryUuid = vm.get(JCR_VERSIONHISTORY, String.class);
            if (versionHistoryUuid != null && historyToVersion.containsKey(versionHistoryUuid)) {
                String requiredVersionUuid = historyToVersion.get(versionHistoryUuid);
                try {
                    Resource version = ResourceUtil.getByUuid(underlyingResolver, requiredVersionUuid);
                    if (version == null) {
                        LOG.error("Version {} does not found for {}", requiredVersionUuid, versionHistoryUuid);
                        return null;
                    }
                    String actualVersionHistoryUuid = version.getParent().getValueMap().get(JCR_UUID, String.class);
                    if (!versionHistoryUuid.equals(actualVersionHistoryUuid)) {
                        LOG.error("Version {} does not belong to {} but to {}", requiredVersionUuid, versionHistoryUuid, actualVersionHistoryUuid);
                        return null;
                    }
                    return version.getChild(JCR_FROZENNODE);
                } catch (RepositoryException e) {
                    LOG.error("Cannot get version {} for {}", requiredVersionUuid, versionHistoryUuid, e);
                    return null;
                }
            }
        }
        return resource;
    }

    @Nonnull
    @Override
    public Iterator<Resource> listChildren(@Nonnull Resource rawParent) {
        final Resource parent = ResourceUtil.unwrap(rawParent);
        StagingResource stagingResource = null;
        if (parent instanceof StagingResource) {
            stagingResource = (StagingResource) parent;
            VersionSelectResourceResolver otherResolver = stagingResource.getResourceResolver().adaptTo(VersionSelectResourceResolver.class);
            if (otherResolver != this) // safety measure - retrieve it by path
                stagingResource = null;
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
                (child) -> child != null && !ResourceUtil.isNonExistingResource(child)
        );
        return resourceIterator;
    }

    @Override
    public @Nonnull
    ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
        ResourceResolver resolver = underlyingResolver.clone(authenticationInfo);
        return new VersionSelectResourceResolver(underlyingResolver, historyToVersion, closeResolverOnClose);
    }

}

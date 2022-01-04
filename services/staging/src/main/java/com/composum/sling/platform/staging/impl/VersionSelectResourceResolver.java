package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.VersionReference;
import com.composum.sling.platform.staging.versions.PlatformVersionsService;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.Validate;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.apache.jackrabbit.JcrConstants.JCR_VERSIONHISTORY;
import static org.apache.jackrabbit.JcrConstants.MIX_VERSIONABLE;

/**
 * A unmodifiable {@link org.apache.sling.api.resource.ResourceResolver} for which one can specify specific versions of
 * {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE} which are presented as if that was the
 * current content. (Unmodifiable in the sense that no content can be modified through it.
 */
public class VersionSelectResourceResolver extends AbstractStagingResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VersionSelectResourceResolver.class);

    @NotNull
    private final Map<String, String> historyToVersion;

    /**
     * Creates a {@link VersionSelectResourceResolver} replacing one versionables with a historical version.
     *
     * @param underlyingResolver   the resolver we use to retrieve things
     * @param closeResolverOnClose if true, the {underlyingResolver} will be closed as well if this resolver is closed
     * @param versionUuids         the {@value org.apache.jackrabbit.JcrConstants#JCR_BASEVERSION} of one ore more replaced
     *                             {@value org.apache.jackrabbit.JcrConstants#MIX_VERSIONABLE}s
     */
    public VersionSelectResourceResolver(@NotNull ResourceResolver underlyingResolver,
                                         boolean closeResolverOnClose, @NotNull String... versionUuids) throws RepositoryException {
        super(underlyingResolver, closeResolverOnClose);
        this.historyToVersion = makeHistoryToVersionMap(underlyingResolver, versionUuids);
    }

    protected VersionSelectResourceResolver(@NotNull ResourceResolver underlyingResolver,
                                            boolean closeResolverOnClose, @NotNull Map<String, String> historyToVersion) {
        super(underlyingResolver, closeResolverOnClose);
        this.historyToVersion = historyToVersion;
    }

    /**
     * Creates a map usable for {@link #VersionSelectResourceResolver(ResourceResolver, boolean, Map)} from a number of versions, retrieving their version history uuids.
     */
    protected static Map<String, String> makeHistoryToVersionMap(@NotNull ResourceResolver resolver, @NotNull String... versions) throws RepositoryException {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (String version : versions) {
            Validate.notNull(version);
            Resource versionResource = ResourceUtil.getByUuid(resolver, version);
            String versionHistoryUuid = versionResource != null ? versionResource.getParent().getValueMap().get(JCR_UUID, String.class) : null;
            if (versionHistoryUuid == null)
                throw new RepositoryException("No version history found for " + version);
            builder.put(versionHistoryUuid, version);
        }
        ImmutableMap<String, String> result = builder.build();
        if (result.size() != versions.length)
            throw new RepositoryException("Several versions for one document: " + Arrays.asList(versions));
        return result;
    }

    @NotNull
    @Override
    protected Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @NotNull String rawPath) {
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

    @Nullable
    public Resource getResource(PlatformVersionsService.Status status) {
        Resource resource = null;
        VersionReference reference = status.getVersionReference();
        if (reference != null) {
            resource = reference.getVersionResource();
            if (resource != null) {
                return wrapIntoStagingResource(status.getPath(), resource.getChild(JCR_FROZENNODE), null, false);
            }
        }
        return null;
    }

    @Override
    @NotNull
    public Resource resolve(@NotNull HttpServletRequest request, @NotNull String rawAbsPath) {
        String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) return new NonExistingResource(this, rawAbsPath);
        Resource resource = request != null ? underlyingResolver.resolve(request, absPath) : underlyingResolver.resolve(absPath);
        return retrieveReleasedResource((SlingHttpServletRequest) request, resource.getPath());
    }

    /**
     * Checks whether the resource is a versionable for which we step into version space.
     */
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

    @NotNull
    @Override
    public Iterator<Resource> listChildren(@NotNull Resource rawParent) {
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
    public @NotNull
    ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
        ResourceResolver resolver = underlyingResolver.clone(authenticationInfo);
        return new VersionSelectResourceResolver(underlyingResolver, closeResolverOnClose, historyToVersion);
    }

}

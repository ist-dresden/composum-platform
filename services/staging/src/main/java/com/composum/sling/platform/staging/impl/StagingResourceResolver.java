package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager.ReleaseImpl;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.impl.QueryBuilderImpl;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;

/**
 * <p>A {@link ResourceResolver} that provides transparent access to releases as defined in {@link StagingReleaseManager}.
 * This is always instantiated through {@link StagingReleaseManager#getResolverForRelease(StagingReleaseManager.Release, ReleaseMapper, boolean)}.
 * </p>
 * <h3>Limitations:</h3>
 * <ul>
 * <li>This returns read-only resources, and querying is only supported through {@link QueryBuilder}.</li>
 * <li>We also don't support {@link org.apache.sling.resourceresolver.impl.params.ParsedParameters} (yet).</li>
 * <li>We don't include synthetic resources or resources of other resource providers (servlets etc.) into releases.</li>
 * </ul>
 */
public class StagingResourceResolver extends AbstractStagingResourceResolver implements ResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResourceResolver.class);

    @Nonnull
    protected final StagingReleaseManager.Release release;

    @Nonnull
    protected final ReleaseMapper releaseMapper;

    @Nonnull
    protected final DefaultStagingReleaseManager.Configuration configuration;

    /**
     * Instantiates a new Staging resource resolver.
     *
     * @param release              the release
     * @param underlyingResolver   the resolver used to access resources outside of the version space
     * @param releaseMapper        the release mapper that determines which resources are release-mapped
     * @param configuration        the configuration
     * @param closeResolverOnClose if true, the underlyingResolver is closed when this resolver is closed
     */
    protected StagingResourceResolver(@Nonnull StagingReleaseManager.Release release, @Nonnull ResourceResolver underlyingResolver, @Nonnull ReleaseMapper releaseMapper, @Nonnull DefaultStagingReleaseManager.Configuration configuration, boolean closeResolverOnClose) {
        super(underlyingResolver, closeResolverOnClose);
        this.release = release;
        this.releaseMapper = releaseMapper;
        this.configuration = configuration;
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

    /**
     * The resolver we use inside to access the release data.
     *
     * @deprecated that should go somehow through resolver methods.
     */
    @Nonnull
    @Deprecated
    public ResourceResolver getUnderlyingResolver() {
        return underlyingResolver;
    }

    /** Checks whether the path is one of the special paths that are overlayed from the workspace. */
    public boolean isDirectlyMappedPath(String path) {
        String rootPath = release.getReleaseRoot().getPath();
        if (release.appliesToPath(path)) {
            for (String node : configuration.overlayed_nodes()) {
                if (SlingResourceUtil.isSameOrDescendant(rootPath + '/' + node, path))
                    return true;
            }
        }
        return false;
    }

    /** Checks whether the path is a special paths that is removed by the resolver. */
    protected boolean isFilteredPath(String path) {
        String rootPath = release.getReleaseRoot().getPath();
        if (release.appliesToPath(path)) {
            for (String removedPath : configuration.removed_paths()) {
                if (SlingResourceUtil.isSameOrDescendant(rootPath + '/' + removedPath, path))
                    return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isFiltered(Resource resource) {
        return isFilteredPath(resource.getPath());
    }

    /** Returns additional wrapped children overlayed into the release - primarily for {@link #isDirectlyMappedPath(String)}. */
    @Override
    @Nullable
    protected Iterator<Resource> overlayedChildren(@Nonnull Resource parent) {
        if (release.getReleaseRoot().getPath().equals(parent.getPath())) {
            List<Resource> overlayedNodes =
                    Arrays.stream(configuration.overlayed_nodes())
                            .map(release.getReleaseRoot()::getChild)
                            .filter(Objects::nonNull)
                            .map(
                                    (child) -> wrapIntoStagingResource(child.getPath(), child, null, false)
                            )
                            .collect(Collectors.toList());
            return overlayedNodes.iterator();
        }
        return null;
    }

    /**
     * Finds the simulated resource. The meat of the actual retrieval algorithm.  @param request the request
     *
     * @param rawPath an absolute path (possibly not normalized)
     * @return the resource or a {@link NonExistingResource} if it isn't present somewhere or in the release
     */
    @Override
    @Nonnull
    protected Resource retrieveReleasedResource(@Nullable SlingHttpServletRequest request, @Nonnull String rawPath) {
        Validate.isTrue(rawPath.startsWith("/"), "Absolute path required, but got %s", rawPath);
        String path = ResourceUtil.normalize(rawPath);
        if (path == null || isFilteredPath(path)) // weird path like /../.. or explicitly removed
            return new NonExistingResource(this, rawPath);

        if (!releaseMapper.releaseMappingAllowed(path) || !release.appliesToPath(path) || isDirectlyMappedPath(path)) {
            // we need to return a StagingResource, too, since e.g. listChildren might go into releasemapped areas.
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
                if (underlyingResource == null) return new NonExistingResource(this, path);
                String actualname = StagingUtils.isInStorage(underlyingResource) ?
                        REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(level, level) // mapping for property resources
                        : level;
                underlyingResource = underlyingResource.getChild(actualname);
                underlyingResource = stepResource(underlyingResource);
            }
        }
        return wrapIntoStagingResource(path, underlyingResource, request, true);
    }

    /**
     * Checks whether the resource is exactly on one of the points where we move to a different resource:
     * the release root is actually mapped to the release content root, and a version reference mapped to version space.
     */
    @Override
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
        } else if (resource.getValueMap().get(StagingConstants.PROP_DEACTIVATED, false)) {
            return null;
        }
        return resource;
    }

    @Override
    @Nonnull
    public Resource resolve(@Nullable HttpServletRequest request, @Nonnull String rawAbsPath) {
        String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) return new NonExistingResource(this, rawAbsPath);
        Resource resource = request != null ? underlyingResolver.resolve(request, absPath) : underlyingResolver.resolve(absPath);
        if (!releaseMapper.releaseMappingAllowed(absPath) || !release.appliesToPath(absPath))
            return wrapIntoStagingResource(resource.getPath(), resource, request, true);
        return retrieveReleasedResource((SlingHttpServletRequest) request, resource.getPath());
    }

    // ------------------------- Start of the easy parts
    // unsupported modification methods and simply forwarded to underlyingResolver
    // or can just be implemented in terms of other methods.

    @Override
    @Nonnull
    public ResourceResolver clone(@Nullable Map<String, Object> authenticationInfo) throws LoginException {
        ResourceResolver resolver = underlyingResolver.clone(authenticationInfo);
        return new StagingResourceResolver(release, resolver, releaseMapper, configuration, true);
    }
    
    /**
     * {@inheritDoc}
     * CAUTION: we have to support the JCR {@link Session} here, since it's often used to access various
     * managers, but the session does not support our simulated resources. Use at your own risk.
     */
    @Override
    @Nullable
    public <AdapterType> AdapterType adaptTo(@Nonnull Class<AdapterType> type) {
        if (QueryBuilder.class.equals(type)) { return type.cast(new QueryBuilderImpl(this)); }
        return super.adaptTo(type);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append(release)
                .toString();
    }
}

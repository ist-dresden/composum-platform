package com.composum.sling.platform.staging.service;

import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingResourceResolver;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceNotFoundException;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.List;

/**
 * <p>Functionality to manage releases that are accessible via the {@link com.composum.sling.platform.staging.StagingResourceResolver}
 * or via replication.</p>
 * <p>
 * For a folder / site to be releasable it's root has to have the mixin {@value StagingConstants#NODE_RELEASES},
 * and it needs have a jcr:content node with mixin {@value StagingConstants#TYPE_MIX_RELEASE_CONFIG}
 * with a subnode {@value StagingConstants#NODE_RELEASES} that contains one subnode for each release.
 * The current (open) release is named {@value StagingConstants#NODE_CURRENT_RELEASE}.
 * Each of these release nodes can contain metadata about the release, and below a subnode
 * {@value StagingConstants#NODE_RELEASE_ROOT} a copy of the unversioned parts of the page tree with the {@value com.composum.sling.core.util.ResourceUtil#TYPE_VERSIONABLE}
 * nodes replaced by {@value StagingConstants#TYPE_VERSIONREFERENCE}s.
 * </p>
 * <pre>
 * siteRoot[{@value StagingConstants#NODE_RELEASES}]/
 *     jcr:content[{@value StagingConstants#TYPE_MIX_RELEASE_CONFIG}]/
 *         {@value StagingConstants#NODE_RELEASES}/
 *             {@value StagingConstants#NODE_CURRENT_RELEASE}/
 *                 {@value StagingConstants#NODE_RELEASE_ROOT}
 *                     ... copy of the working tree for the release
 *                 {@value StagingConstants#NODE_RELEASE_METADATA}
 *             otherrelase
 *                 {@value StagingConstants#NODE_RELEASE_ROOT}
 *                     ... copy of the working tree for the release
 *                 {@value StagingConstants#NODE_RELEASE_METADATA}
 * </pre>
 * <p>TODO: perhaps rename it to ReleaseManager once the old ReleaseManager is removed.</p>
 */
public interface StagingReleaseManager extends StagingConstants {

    /**
     * Looks up the {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} containing the resource - possibly resource itself.
     *
     * @return the release root or null if there is none.
     */
    @Nullable
    Resource findReleaseRoot(@Nonnull Resource resource);

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * information about all releases at this release root.
     *
     * @param resource a release root or it's subnodes
     * @return possibly empty list of releases
     */
    @Nonnull
    List<Release> getReleases(@Nonnull Resource resource);

    /**
     * Updates the structure of the {@value StagingConstants#NODE_CURRENT_RELEASE} according to the working tree.
     * This also initializes the node structure - it doesn't hurt to call that often.
     *
     * @param a release root or a resource below a release root
     * @throws ResourceNotFoundException if resource is not a release root or below a release root
     */
    void updateCurrentReleaseFromWorkspace(@Nonnull Resource resource) throws ResourceNotFoundException, PersistenceException, RepositoryException;

    /**
     * Creates a {@link StagingResourceResolver} that presents the given release.
     *
     * @param releaseRoot  the root of the release
     * @param releaseLabel a label for a release, possibly {@value StagingConstants#NODE_CURRENT_RELEASE} for the current release.
     * @return the resolver, null if there is no such release.
     */
    @Nullable
    ResourceResolver resolverForRelease(@Nonnull Resource releaseRoot, @Nonnull String releaseLabel);

    /**
     * Data structure with metadata information about a release.
     */
    interface Release {
        /** Internal name (JCR compatible) of the release. */
        String getLabel();

        /** The resource that is the top of the working tree - a {@value StagingConstants#TYPE_MIX_RELEASE_ROOT}. */
        Resource getReleaseRoot();

        /** The resource that contains metadata for this release. */
        Resource getMetaDataNode();

        /**
         * The resource that contains the metadata for the release - including the subnode {@value StagingConstants#NODE_RELEASE_ROOT}
         * with the copy of the data. Don't touch {@value StagingConstants#NODE_RELEASE_ROOT} - always use the
         * {@link StagingReleaseManager} for that!
         */
        Resource getReleaseNode();

        /**
         * Checks whether the given path is in the range of the release root. This does not check whether the resource actually exists.
         *
         * @param path an absolute path
         * @return true if it's within the tree spanned by the release root.
         */
        boolean appliesToPath(@Nullable String path);
    }

}

package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.version.Version;
import java.util.List;
import java.util.Map;

/**
 * <p>Functionality to manage releases that are accessible via the {@link com.composum.sling.platform.staging.StagingResourceResolver}
 * or via replication.</p>
 * <p>
 * For a folder / site to be releasable its root has to have the mixin {@value StagingConstants#NODE_RELEASES},
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
 * // FIXME hps 2019-04-05 introduce open / immutable release state
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
     * @param resource a release root or its subnodes
     * @return possibly empty list of releases
     */
    @Nonnull
    List<Release> getReleases(@Nonnull Resource resource);

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * the release with the given <code>releaseNumber</code> ( {@link Release#getNumber()} ), if there is one.
     *
     * @param resource     a release root or its subnodes
     * @param releaseNumber a label for a release, possibly {@value StagingConstants#NODE_CURRENT_RELEASE} for the current release.
     * @return the release
     * @throws ReleaseNotFoundException if the release wasn't found
     */
    @Nonnull
    Release findRelease(@Nonnull Resource resource, @Nonnull String releaseNumber) throws ReleaseNotFoundException;

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * the release with the given uuid <code>releaseUuid</code> (see {@link Release#getUuid()} , if there is one.
     *
     * @param resource    a release root or its subnodes
     * @param releaseUuid the {@link Release#getUuid()}
     * @throws ReleaseNotFoundException if the release wasn't found
     */
    @Nonnull
    Release findReleaseByUuid(@Nonnull Resource resource, @Nonnull String releaseUuid) throws ReleaseNotFoundException;

    /**
     * Creates a new release. The release content is copied from the latest release (in the sense of
     * {@link ReleaseNumberCreator#COMPARATOR_RELEASES}) and the release number is created using {releaseType}.
     * If there was no release yet, it's copied from the {@link StagingConstants#NODE_CURRENT_RELEASE} release
     * (which can possibly be empty).
     *
     * @param resource             release root or one of its subnodes
     * @param releaseType how to create the releae number - major, minor or bugfix release
     */
    Release createRelease(@Nonnull Resource resource, @Nonnull ReleaseNumberCreator releaseType)
            throws PersistenceException, RepositoryException;

    /**
     * Creates a release from another release. This also creates a new version number for that release
     * based on the copied release - which can lead to conflicts ( {@link ReleaseExistsException} )
     * if there is already a release with that number.
     *
     * @param copyFromRelease optionally, an existing release, whose workspace is copied.
     * @param releaseType     how to create the releae number - major, minor or bugfix release
     * @throws ReleaseExistsException if the release already exists
     */
    Release createRelease(@Nonnull Release copyFromRelease, @Nonnull ReleaseNumberCreator releaseType)
            throws ReleaseExistsException, PersistenceException, RepositoryException;

    /** Gives information about a releases contents. */
    @Nonnull
    List<ReleasedVersionable> listReleaseContents(@Nonnull Release release);

    /**
     * Updates the release by adding or updating the versionable denoted by {releasedVersionable} in the release.
     * If {@link ReleasedVersionable#versionUuid} is null, it is removed from the release.
     *
     * @param release          the release to update
     * @param releasedVersionable information of the versionable to update
     * @throws ReleaseNotFoundException if the copied release doesn't exist
     * @return a map with paths where we changed the order of children in the release.
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> updateRelease(@Nonnull Release release, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException, PersistenceException;

    /**
     * Updates the release by adding or updating a number of versionables denoted by {releasedVersionable} in the release.
     * If {@link ReleasedVersionable#versionUuid} is null, it is removed from the release.
     *
     * @param release                 the release to update
     * @param releasedVersionableList a number of paths to versionables for which the latest version should be put into the release
     * @return a map with paths where we changed the order of children in the release.
     * @throws ReleaseNotFoundException if the copied release doesn't exist
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> updateRelease(@Nonnull Release release, @Nonnull List<ReleasedVersionable> releasedVersionableList) throws RepositoryException, PersistenceException;

    /**
     * Removes the release. Deleting the {@link StagingConstants#NODE_CURRENT_RELEASE} is also possible, though it'll
     * be recreated automatically when calling one of the get / find release methods.
     *
     * @param release the release, as given by {@link #findRelease(Resource, String)} or {@link #findReleaseByUuid(Resource, String)}.
     * @throws PersistenceException can happen e.g. when deleting a release that is referenced somewhere
     */
    void removeRelease(@Nonnull Release release) throws PersistenceException;

    /**
     * Creates a {@link StagingResourceResolver} that presents the given release.
     *
     * @param release the release for which the resolver is created
     * @param releaseMapper controls what is mapped into the release. If null, we just use one that always returns true
     * @param closeResolverOnClose if true, the resolver for the resources contained in {release} is closed when the returned resolver is closed
     * @return the resolver
     * @see #getReleases(Resource)
     */
    @Nonnull
    ResourceResolver getResolverForRelease(@Nonnull Release release, @Nullable ReleaseMapper releaseMapper, boolean closeResolverOnClose);

    /** Describes the state of a versionable in a release. Can also be used as parameter object to update the release. */
    public class ReleasedVersionable {

        /** @see #getRelativePath() */
        private String relativePath;

        /** Path relative to release root. */
        public String getRelativePath() {
            return relativePath;
        }

        /** @see #getRelativePath() */
        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }

        /** @see #getVersionableUuid() */
        private String versionableUuid;

        /** {@value com.composum.sling.core.util.ResourceUtil#PROP_UUID} of the versionable that was put into the release. */
        public String getVersionableUuid() {
            return versionableUuid;
        }

        /** @see #getVersionableUuid() */
        public void setVersionableUuid(String versionableUuid) {
            this.versionableUuid = versionableUuid;
        }

        /** @see #getVersionUuid() */
        private String versionUuid;

        /** {@link Version#getUUID()} of the version of the versionable that is in the release / is to be put into the release.. */
        public String getVersionUuid() {
            return versionUuid;
        }

        /** @see #getVersionUuid() */
        public void setVersionUuid(String versionUuid) {
            this.versionUuid = versionUuid;
        }

        /** @see #getVersionHistory() */
        private String versionHistory;

        /** The UUID of the version history, as unchangeable identifier. */
        public String getVersionHistory() {
            return versionHistory;
        }

        /** @see #getVersionHistory() */
        public void setVersionHistory(String versionHistory) {
            this.versionHistory = versionHistory;
        }

        /** @see #getActive() */
        private Boolean active;

        /** Whether the versionable is active in the release. */
        public Boolean getActive() {
            return active;
        }

        /** @see #getActive() */
        public void setActive(Boolean active) {
            this.active = active;
        }

        /** Creates a {@link ReleasedVersionable} that corresponds to the base version of the given versionable. */
        public static ReleasedVersionable forBaseVersion(@Nonnull Resource resource) {
            if (!ResourceUtil.isResourceType(resource, ResourceUtil.TYPE_VERSIONABLE))
                throw new IllegalArgumentException("resource is not versionable: " + SlingResourceUtil.getPath(resource));
            ReleasedVersionable result = new ReleasedVersionable();
            Resource releaseRoot = resource;
            StringBuilder relPath = new StringBuilder();
            while (!ResourceUtil.isResourceType(releaseRoot, TYPE_MIX_RELEASE_ROOT)) {
                if (relPath.length() > 0) relPath.insert(0, '/');
                relPath.insert(0, releaseRoot.getName());
                releaseRoot = releaseRoot.getParent();
            }
            result.setRelativePath(relPath.toString());
            result.setActive(true);
            result.setVersionableUuid(resource.getValueMap().get(ResourceUtil.PROP_UUID, String.class));
            result.setVersionUuid(resource.getValueMap().get(JcrConstants.JCR_BASEVERSION, String.class));
            result.setVersionHistory(resource.getValueMap().get(JcrConstants.JCR_VERSIONHISTORY, String.class));
            return result;
        }

        /** Releasemanager internal: creates a {@link ReleasedVersionable} that corresponds to a {@link StagingConstants#TYPE_VERSIONREFERENCE}. */
        public static ReleasedVersionable fromVersionReference(@Nonnull Resource treeRoot, @Nonnull Resource resource) {
            if (!ResourceUtil.isResourceType(resource, StagingConstants.TYPE_VERSIONREFERENCE)) {
                throw new IllegalArgumentException("resource is not version reference: " + SlingResourceUtil.getPath(resource));
            }
            if (!resource.getPath().equals(treeRoot) && !resource.getPath().startsWith(treeRoot.getPath() + '/')) {
                throw new IllegalArgumentException("Resource not in treeroot: " + resource.getPath() + ", " + treeRoot.getPath());
            }

            ReleasedVersionable result = new ReleasedVersionable();
            ResourceHandle rh = ResourceHandle.use(resource);

            result.setActive(!rh.getProperty(StagingConstants.PROP_DEACTIVATED, Boolean.FALSE));
            result.setVersionableUuid(rh.getProperty(StagingConstants.PROP_VERSIONABLEUUID, String.class));
            result.setVersionUuid(rh.getProperty(StagingConstants.PROP_VERSION, String.class));
            result.setVersionHistory(rh.getProperty(StagingConstants.PROP_VERSIONHISTORY, String.class));
            result.setRelativePath(StringUtils.removeStart(resource.getPath().substring(treeRoot.getPath().length()), "/"));

            return result;
        }

        /** Releasemanager internal: writes values into a versionreference. */
        public void writeToVersionReference(@Nonnull Resource versionReference) throws RepositoryException {
            ResourceHandle rh = ResourceHandle.use(versionReference);
            String oldVersionHistory = rh.getProperty(StagingConstants.PROP_VERSIONHISTORY);
            if (oldVersionHistory != null && !oldVersionHistory.equals(getVersionHistory()))
                throw new IllegalArgumentException("Trying to write to different versionhistory: " + getVersionHistory() + " to " + oldVersionHistory);
            if (getActive() != null) rh.setProperty(StagingConstants.PROP_DEACTIVATED, !getActive());
            rh.setProperty(StagingConstants.PROP_VERSIONABLEUUID, getVersionableUuid(), PropertyType.WEAKREFERENCE);
            rh.setProperty(StagingConstants.PROP_VERSION, getVersionUuid(), PropertyType.REFERENCE);
            rh.setProperty(StagingConstants.PROP_VERSIONHISTORY, getVersionHistory(), PropertyType.REFERENCE);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ReleasedVersionable{");
            sb.append("relativePath='").append(relativePath).append('\'');
            sb.append(", versionableUuid='").append(versionableUuid).append('\'');
            sb.append(", versionUuid='").append(versionUuid).append('\'');
            sb.append(", versionHistory='").append(versionHistory).append('\'');
            sb.append(", active=").append(active);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * Data structure with metadata information about a release. This must only be created within the {@link StagingReleaseManager}.
     */
    interface Release {

        /**
         * The UUID of the top node with the release data. You can store this as property type {@link javax.jcr.PropertyType#REFERENCE}
         * somewhere to make sure a published release is not deleted.
         */
        @Nonnull
        String getUuid();

        /**
         * Release number (JCR compatible) of the release: this is automatically created with {@link ReleaseNumberCreator}
         * and will be something like r4 or r2.4.5 .
         */
        @Nonnull
        String getNumber();

        /** The resource that is the top of the working tree - a {@value StagingConstants#TYPE_MIX_RELEASE_ROOT}. */
        @Nonnull
        Resource getReleaseRoot();

        /**
         * The resource that contains metadata for this release. This is not touched by the {@link StagingReleaseManager}
         * and can be used to store additional metadata.
         */
        @Nonnull
        Resource getMetaDataNode();

        /**
         * Checks whether the given path is in the range of the release root. This does not check whether the resource actually exists.
         *
         * @param path an absolute path
         * @return true if it's within the tree spanned by the release root.
         */
        boolean appliesToPath(@Nullable String path);
    }

    /**
     * Is thrown when a release label given as argument is not found for a release root.
     * This is a runtime exception since this is not something
     */
    class ReleaseNotFoundException extends RuntimeException {
        // empty
    }

    /** Is thrown when a release label given as argument is not found for a release root. */
    class ReleaseExistsException extends Exception {
        private final Resource releaseRoot;
        private final String releaseNumber;

        public ReleaseExistsException(Resource releaseRoot, String releaseNumber) {
            super("Release already exists: " + releaseNumber + " for " + releaseRoot.getPath());
            this.releaseRoot = releaseRoot;
            this.releaseNumber = releaseNumber;
        }

        public Resource getReleaseRoot() {
            return releaseRoot;
        }

        public String getReleaseNumber() {
            return releaseNumber;
        }
    }

}

package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Map;

/**
 * <p>Functionality to manage releases that are accessible via the {@link com.composum.sling.platform.staging.StagingResourceResolver}
 * or via replication.</p>
 * <p>
 * For a folder / site to be releasable its root has to have the mixin {@value StagingConstants#NODE_RELEASES},
 * and it needs have a jcr:content node with mixin {@value StagingConstants#TYPE_MIX_RELEASE_CONFIG}
 * with a subnode {@value StagingConstants#NODE_RELEASES} that contains one subnode for each release.
 * The current (open) release is named {@value StagingConstants#CURRENT_RELEASE}.
 * Each of these release nodes can contain metadata about the release, and below a subnode
 * {@value StagingConstants#NODE_RELEASE_ROOT} a copy of the unversioned parts of the page tree with the {@value com.composum.sling.core.util.ResourceUtil#TYPE_VERSIONABLE}
 * nodes replaced by {@value StagingConstants#TYPE_VERSIONREFERENCE}s.
 * </p>
 * <pre>
 * siteRoot[{@value StagingConstants#NODE_RELEASES}]/
 *     jcr:content[{@value StagingConstants#TYPE_MIX_RELEASE_CONFIG}]/
 *         {@value StagingConstants#NODE_RELEASES}/
 *             {@value StagingConstants#CURRENT_RELEASE}/
 *                 {@value StagingConstants#NODE_RELEASE_ROOT}
 *                     ... copy of the working tree for the release
 *                 {@value StagingConstants#NODE_RELEASE_METADATA}
 *             otherrelase
 *                 {@value StagingConstants#NODE_RELEASE_ROOT}
 *                     ... copy of the working tree for the release
 *                 {@value StagingConstants#NODE_RELEASE_METADATA}
 * </pre>
 * <p>We also set a label {@link Release#getReleaseLabel()} on each version contained in the release,
 * for easier referencing versions. </p>
 * // TODO hps 2019-04-05 introduce open / immutable release state
 */
public interface StagingReleaseManager {

    /**
     * Looks up the {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} containing the resource - possibly resource itself.
     *
     * @param resource a release root or its subnodes
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
     * @param resource      a release root or its subnodes
     * @param releaseNumber a label for a release, possibly {@value StagingConstants#CURRENT_RELEASE} for the current release.
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
     * If there was no release yet, it's copied from the {@link StagingConstants#CURRENT_RELEASE} release
     * (which can possibly be empty).
     *
     * @param resource    release root or one of its subnodes
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

    /** Gives information about a releases contents. Caution: this finds only committed content. */
    @Nonnull
    List<ReleasedVersionable> listReleaseContents(@Nonnull Release release) throws RepositoryException;

    /**
     * Lists the current content (using {@link ReleasedVersionable#forBaseVersion(Resource)}). Caution: this finds only committed content.
     *
     * @param resource a release root or its subnodes
     */
    @Nonnull
    List<ReleasedVersionable> listCurrentContents(@Nonnull Resource resource) throws RepositoryException;

    /**
     * Looks up whether a versionable is present in the release. Caution: this finds only committed content.
     *
     * @param versionHistoryUuid the version history uuid (not the version uuid!)
     * @return the information about the item in the release, if it is present
     */
    @Nullable
    ReleasedVersionable findReleasedVersionableByUuid(@Nonnull Release release, @Nonnull String versionHistoryUuid) throws RepositoryException;

    /**
     * Looks up whether a versionable is present in the release.
     *
     * @param versionable the versionable, from which we take the versionHistoryUuid to look it up in the release
     * @return the information about the item in the release, if it is present
     */
    @Nullable
    ReleasedVersionable findReleasedVersionable(@Nonnull Release release, @Nonnull Resource versionable) throws RepositoryException;

    /**
     * Updates the release by adding or updating the versionable denoted by {releasedVersionable} in the release.
     * If {@link ReleasedVersionable#versionUuid} is null, it is removed from the release.
     * We also set a label {@value StagingConstants#RELEASE_LABEL_PREFIX}{releasenumber} on each version contained in the release,
     * for easier referencing versions. Caution: the current release is called {@value StagingConstants#RELEASE_LABEL_PREFIX}current .
     *
     * @param release             the release to update
     * @param releasedVersionable information of the versionable to update
     * @return a map with paths where we changed the order of children in the release.
     * @throws ReleaseNotFoundException if the copied release doesn't exist
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> updateRelease(@Nonnull Release release, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException, PersistenceException;

    /**
     * Updates the release by adding or updating a number of versionables denoted by {releasedVersionable} in the release.
     * If {@link ReleasedVersionable#versionUuid} is null, it is removed from the release.
     * We also set a label {@value StagingConstants#RELEASE_LABEL_PREFIX}{releasenumber} on each version contained in the release,
     * for easier referencing versions. Caution: the current release is called {@value StagingConstants#RELEASE_LABEL_PREFIX}current .
     *
     * @param release                 the release to update
     * @param releasedVersionableList a number of paths to versionables for which the latest version should be put into the release
     * @return a map with paths where we changed the order of children in the release.
     * @throws ReleaseNotFoundException if the copied release doesn't exist
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> updateRelease(@Nonnull Release release, @Nonnull List<ReleasedVersionable> releasedVersionableList) throws RepositoryException, PersistenceException;

    /**
     * Removes the release. Deleting the {@link StagingConstants#CURRENT_RELEASE} is also possible, though it'll
     * be recreated automatically when calling one of the get / find release methods.
     *
     * @param release the release, as given by {@link #findRelease(Resource, String)} or {@link #findReleaseByUuid(Resource, String)}.
     * @throws PersistenceException can happen e.g. when deleting a release that is referenced somewhere
     */
    void removeRelease(@Nonnull Release release) throws PersistenceException;

    /**
     * Creates a {@link StagingResourceResolver} that presents the given release.
     *
     * @param release              the release for which the resolver is created
     * @param releaseMapper        controls what is mapped into the release. If null, we just use one that always returns true
     * @param closeResolverOnClose if true, the resolver for the resources contained in {release} is closed when the returned resolver is closed
     * @return the resolver
     * @see #getReleases(Resource)
     */
    @Nonnull
    ResourceResolver getResolverForRelease(@Nonnull Release release, @Nullable ReleaseMapper releaseMapper, boolean closeResolverOnClose);

    /**
     * Sets a mark to the given release. Each mark (e.g. public, preview) can apply only to at most one release.
     * If a release is marked, it cannot be deleted by {@link #removeRelease(Release)}.
     *
     * @param mark    a nonempty string usable as attribute name
     * @param release a release
     */
    void setMark(@Nonnull String mark, @Nonnull Release release) throws RepositoryException;

    /**
     * Removes the mark from the given release.
     *
     * @param mark    a nonempty string usable as attribute name
     * @param release a release
     * @throws IllegalArgumentException if the release does not carry that mark
     */
    void deleteMark(@Nonnull String mark, @Nonnull Release release) throws RepositoryException;


    /**
     * Deletes a unmarked release. If you want to delete a release marked with {@link #setMark(String, Release)},
     * you have to {@link #deleteMark(String, Release)} first.
     *
     * @param release the release to delete
     * @throws RepositoryException
     */
    void deleteRelease(@Nonnull Release release) throws RepositoryException, PersistenceException;

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * the release with the given <code>mark</code> ( e.g. public or preview ), if there is one.
     *
     * @param resource a release root or its subnodes
     * @param mark     a nonempty string usable as attribute name
     * @return the marked release, or null if the mark isn't set.
     */
    @Nullable
    Release findReleaseByMark(@Nonnull Resource resource, @Nonnull String mark);

    /**
     * Returns the release to which the {releaseResource} (e.g. the metaData node of the release) belongs.
     *
     * @param releaseResource the release node (release root)/jcr:content/cpl:releases/{releasenumber} or one of its subnodes
     * @return the release where releaseResource belongs, null if it doesn't
     */
    @Nullable
    Release findReleaseByReleaseResource(@Nullable Resource releaseResource);

    /**
     * Restores a deleted versionable that is contained in a release.
     *
     * @param release             a release where the versionable still exists
     * @param releasedVersionable the version information on what to restore. If you want a different path or version,
     *                            you can use {@link ReleasedVersionable#setRelativePath(String)}.
     * @return information about the restored versionable ({@link ReleasedVersionable#forBaseVersion(Resource)}).
     */
    @Nonnull
    ReleasedVersionable restore(@Nonnull Release release, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException;


    /**
     * Checks whether the versionables contained below the release root have labels starting with {@link StagingConstants#RELEASE_LABEL_PREFIX}
     * but aren't in the corresponding release.
     *
     * @param resource the release root or something below it
     * @throws RepositoryException
     */
    void cleanupLabels(@Nonnull Resource resource) throws RepositoryException;

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
         * and will be something like r4 or r2.4.5 . The current release is called {@value StagingConstants#CURRENT_RELEASE}.
         */
        @Nonnull
        String getNumber();

        /**
         * Returns the label that is set on the versions, additionally to being stored in the content copy.
         * This is {@value StagingConstants#RELEASE_LABEL_PREFIX}{number} - except for the current
         * release where it's called {@value StagingConstants#RELEASE_LABEL_PREFIX}current,
         * since a label must not contain a colon.
         */
        @Nonnull
        default String getReleaseLabel() {
            return StagingConstants.RELEASE_LABEL_PREFIX + getNumber().replace("cpl:", "");
        }

        /** The resource that is the top of the working tree - a {@value StagingConstants#TYPE_MIX_RELEASE_ROOT}. */
        @Nonnull
        Resource getReleaseRoot();

        /**
         * The resource that contains metadata for this release. This is not touched by the {@link StagingReleaseManager}
         * and can be used to store additional metadata.
         */
        @Nonnull
        Resource getMetaDataNode();

        /** The marks that point to this release. Each mark can only point to exactly one release. */
        @Nonnull
        List<String> getMarks();

        /**
         * Checks whether the given path is in the range of the release root. This does not check whether the resource actually exists.
         *
         * @param path an absolute path
         * @return true if it's within the tree spanned by the release root.
         */
        boolean appliesToPath(@Nullable String path);

        /** Maps the relative path to the absolute path ( {@link #getReleaseRoot()} + '/' + relativePath ) */
        @Nonnull
        String absolutePath(@Nonnull String relativePath);
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

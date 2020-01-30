package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
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
 * <p>Functionality to manage releases that are accessible via the {@link StagingResourceResolver} or via replication
 * .</p>
 * <p>
 * For a folder / site to be releasable its root has to have the mixin {@value StagingConstants#NODE_RELEASES}.
 * The release data is saved below <code></code>/var/composum/{path to release root}/</code> that contains one subnode for each release.
 * The current (open) release is named {@value StagingConstants#CURRENT_RELEASE}. This is the primary release the users work on.
 * Each of these release nodes can contain metadata about the release, and below a subnode
 * {@value StagingConstants#NODE_RELEASE_ROOT} a copy of the unversioned parts of the page tree with the {@value com.composum.sling.core.util.ResourceUtil#TYPE_VERSIONABLE}
 * nodes replaced by {@value StagingConstants#TYPE_VERSIONREFERENCE}s.
 * </p>
 * <pre>
 * /var/composum/{path to release root}/
 *      {@value StagingConstants#NODE_RELEASES}/
 *         {@value StagingConstants#CURRENT_RELEASE}/
 *               {@value StagingConstants#NODE_RELEASE_ROOT}
 *                   ... copy of the working tree for the release
 *             {@value StagingConstants#NODE_RELEASE_METADATA}
 *         otherrelase
 *             {@value StagingConstants#NODE_RELEASE_ROOT}
 *                 ... copy of the working tree for the release
 *             {@value StagingConstants#NODE_RELEASE_METADATA}
 * </pre>
 * <p>We also set a label {@link Release#getReleaseLabel()} on each version contained in the release,
 * for easier referencing versions. </p>
 * <p>Each release references a {@link Release#getPreviousRelease()} from which it was created.</p>
 */
public interface StagingReleaseManager {

    /**
     * Looks up the {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} containing the resource - possibly resource itself.
     *
     * @param resource a release root or its subnodes
     * @return the release root - a {@link ResourceHandle#isValid()} handle
     * @throws ReleaseRootNotFoundException if the resource is not below a {@link ResourceHandle#isValid()} release root
     * @throws IllegalArgumentException     if resource is null
     */
    @Nonnull
    Resource findReleaseRoot(@Nonnull Resource resource) throws ReleaseRootNotFoundException, IllegalArgumentException;

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * information about all releases at this release root.
     *
     * @param resource a release root or its subnodes
     * @return possibly empty list of releases , sorted with {@link ReleaseNumberCreator#COMPARATOR_RELEASES}
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
     * @throws ReleaseRootNotFoundException resource wasn't below a release root
     */
    @Nonnull
    Release findRelease(@Nonnull Resource resource, @Nonnull String releaseNumber) throws ReleaseNotFoundException, ReleaseRootNotFoundException;

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
     * Creates a release from another release. This also creates a new version number for that release
     * based on the copied release - which can lead to conflicts ( {@link ReleaseExistsException} )
     * if there is already a release with that number.
     * <p>
     * We cannot create a release from {@link StagingConstants#CURRENT_RELEASE} since that does not in the release number
     * as tree concept, so an {@link IllegalArgumentException} would be thrown. For that use the
     * conceptually different {@link #finalizeCurrentRelease(Resource, ReleaseNumberCreator)}.
     * </p>
     *
     * @param copyFromRelease an existing release, whose workspace is copied. Cannot be {@link StagingConstants#CURRENT_RELEASE}.
     * @param releaseType     how to create the release number - major, minor or bugfix release
     * @throws ReleaseExistsException if the release already exists
     * @deprecated in practice, {@link #finalizeCurrentRelease(Resource, ReleaseNumberCreator)} should be used.
     */
    @Nonnull
    @Deprecated
    Release createRelease(@Nonnull Release copyFromRelease, @Nonnull ReleaseNumberCreator releaseType)
            throws ReleaseExistsException, PersistenceException, RepositoryException;

    /**
     * Removes the content of the {@link StagingConstants#CURRENT_RELEASE} and copies it freshly from the
     * given release, remembering it as its {@link Release#getPreviousRelease()}.
     *
     * @param release the release to copy.
     * @return the recreated current release.
     * @throws ReleaseProtectedException if the current release has {@link Release#getMarks()}.
     */
    @Nonnull
    Release resetCurrentTo(@Nonnull Release release) throws PersistenceException, RepositoryException, ReleaseExistsException, ReleaseProtectedException;

    /**
     * Renames the current release to a freshly according to the given scheme created release number
     * and recreates the current release based on that (like {@link #resetCurrentTo(Release)}).
     * The release number is chosen according to the {@link Release#getPreviousRelease()} - it is an error if that already exists.
     *
     * @param resource    the release root or any resource below it to find the release root
     * @param releaseType how to create the release number - major, minor or bugfix release
     * @return the created release
     */
    @Nonnull
    Release finalizeCurrentRelease(@Nonnull Resource resource, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, RepositoryException, PersistenceException;

    /** Gives information about a releases contents. Caution: this finds only committed content. */
    @Nonnull
    List<ReleasedVersionable> listReleaseContents(@Nonnull Release release);

    /**
     * Compares a release to another release or to it's predecessor (if the parameter previousRelease is null).
     * It returns a list of ReleasedVersionable wrt. {release} for which anything was changed - it does not distinguish
     * whether the version is different, the path changed or whether it's new or removed, except that
     * the {@link ReleasedVersionable#versionUuid} is null if it was removed.
     *
     * @param release         the release to compare
     * @param previousRelease optional, a specific release to compare to. If null, we take the previous release, if there is one. If there is no previous release too, we return the release contents (all new).
     * @return The list of changes
     */
    @Nonnull
    List<ReleasedVersionable> compareReleases(@Nonnull Release release, @Nullable Release previousRelease) throws RepositoryException;

    /**
     * Lists the current content (in the workspace, not in the current release, using {@link ReleasedVersionable#forBaseVersion(Resource)}).
     * Caution: this finds only committed content.
     *
     * @param resource a release root or its subnodes
     */
    @Nonnull
    List<ReleasedVersionable> listWorkspaceContents(@Nonnull Resource resource);

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
     * @param versionable the versionable, from which we take the versionHistoryUuid to look it up in the release. If it is a {@link org.apache.sling.api.resource.NonExistingResource},
     *                    we just take the path from it.
     * @return the information about the item in the release, if it is present
     */
    @Nullable
    ReleasedVersionable findReleasedVersionable(@Nonnull Release release, @Nonnull Resource versionable);

    /**
     * Looks up whether a versionable is present in the release.
     *
     * @param path the absolute or release relative path to the versionable
     * @return the information about the item in the release, if it is present
     */
    @Nullable
    ReleasedVersionable findReleasedVersionable(@Nonnull Release release, @Nonnull String path);

    /**
     * Updates the release by adding or updating a number of versionables denoted by {releasedVersionable} in the release.
     * If {@link ReleasedVersionable#versionUuid} is null, it is removed from the release.
     * We also set a label {@value StagingConstants#RELEASE_LABEL_PREFIX}{releasenumber} on each version contained in the release,
     * for easier referencing versions. Caution: the current release is called {@value StagingConstants#RELEASE_LABEL_PREFIX}current .
     * This needs the workspace to copy the attributes and node orderings of the parent nodes from - the paths of the releasedVersionableList must
     * correspond to the workspace.
     *
     * @param release                 the release to update
     * @param releasedVersionableList a number of paths to versionables for which the version denoted by {@link ReleasedVersionable#versionUuid} version should be put into the release, or to be removed when it's null.
     * @return a map with paths where we changed the order of children in the release.
     * @throws ReleaseClosedException if the release is {@link Release#isClosed()}
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> updateRelease(@Nonnull Release release, @Nonnull List<ReleasedVersionable> releasedVersionableList) throws RepositoryException, PersistenceException, ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException;

    /**
     * Restores a versionable to the state it was in a previous release.
     *
     * @param release      the release to change
     * @param pathToRevert the release-relative or absolute path of the versionable in the fromRelease
     * @param fromRelease  the release to copy it from - if it's null we remove it from the release
     * @return a map with paths where we changed the order of children in the release.
     */
    @Nonnull
    Map<String, SiblingOrderUpdateStrategy.Result> revert(@Nonnull Release release, @Nonnull String pathToRevert, @Nullable Release fromRelease) throws RepositoryException,
            PersistenceException, ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException;

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
     * If a release is marked, it cannot be deleted by {@link #deleteRelease(Release)}.
     *
     * @param mark    a nonempty string usable as attribute name
     * @param release a release
     */
    void setMark(@Nonnull String mark, @Nonnull Release release) throws RepositoryException, ReleaseChangeEventListener.ReplicationFailedException;

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
     * Deleting the {@link StagingConstants#CURRENT_RELEASE} is also possible, though it'll
     * be recreated automatically when calling one of the get / find release methods.
     *
     * @param release the release, as given by {@link #findRelease(Resource, String)} or {@link #findReleaseByUuid(Resource, String)}.
     * @throws PersistenceException      can happen e.g. when deleting a release that is referenced somewhere
     * @throws ReleaseProtectedException if a release that has {@link Release#getMarks()} shall be deleted.
     */
    void deleteRelease(@Nonnull Release release) throws RepositoryException, PersistenceException, ReleaseProtectedException;

    /**
     * Looks up the next higher {@link StagingConstants#TYPE_MIX_RELEASE_ROOT} of the resource and returns
     * the release with the given <code>mark</code> ( e.g. public or preview ), if there is one.
     *
     * @param resource a release root or its subnodes
     * @param mark     a nonempty string usable as attribute name
     * @return the marked release, or null if the mark isn't set.
     */
    @Nullable
    Release findReleaseByMark(@Nullable Resource resource, @Nonnull String mark);

    /**
     * Returns the release to which the {releaseResource} (e.g. the metaData node of the release) belongs.
     *
     * @param releaseResource the release node /var/composum/(release root)/cpl:releases/{releasenumber} or one of its subnodes
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
    ReleasedVersionable restoreVersionable(@Nonnull Release release, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException;

    /**
     * Checks whether the versionables contained below the release root have labels starting with {@link StagingConstants#RELEASE_LABEL_PREFIX}
     * but aren't in the corresponding release. For internal purposes.
     *
     * @param resource the release root or something below it
     * @return number of obsolete labels removed
     */
    int cleanupLabels(@Nonnull Resource resource) throws RepositoryException;

    /** Marks a release as {@link Release#isClosed()}. Afterwards, methods like {@link #updateRelease(Release, List)} that change the releases' contents will not work. */
    void closeRelease(@Nonnull Release release) throws RepositoryException;

    /**
     * Changes the {@link StagingConstants#PROP_CHANGE_NUMBER} of a release signifying that the release was changed.
     * This is called internally on each change of the release content. Calling this externally will e.g.
     * trigger a full synchronization the next time any change is transmitted.
     */
    @Nonnull
    String bumpReleaseChangeNumber(@Nonnull Release release) throws RepositoryException;

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
            return StagingConstants.RELEASE_LABEL_PREFIX + getNumber();
        }

        /**
         * Path to internal release node. This is an unique identifier for the release, but use sparingly since this
         * is implementation dependent.
         */
        @Nonnull
        String getPath();

        /** The resource that is the top of the working tree - a {@value StagingConstants#TYPE_MIX_RELEASE_ROOT}. */
        @Nonnull
        Resource getReleaseRoot();

        /**
         * The resource that contains metadata for this release. This is not touched by the {@link StagingReleaseManager}
         * and can be freely used to store additional metadata. If you want to change it, just retrieve the Release object
         * and write on the resource returned here - the {@link StagingReleaseManager} does not care about its contents.
         */
        @Nonnull
        Resource getMetaDataNode();

        /**
         * An UID that changes on each release content change. If it's constant, you can rely on the release content
         * being the same. (Unless for changes not using the {@link StagingReleaseManager}, which you shouldn't do,
         * of course. At least not without calling {@link #bumpReleaseChangeNumber(Release)}, too.)
         */
        @Nonnull
        String getChangeNumber();

        /** The marks that point to this release. Each mark can only point to exactly one release. */
        @Nonnull
        List<String> getMarks();

        /** If true the {@link StagingReleaseManager} will refuse to change the releases contents. */
        boolean isClosed();

        /** Returns the release from which this release was created. */
        @Nullable
        Release getPreviousRelease() throws RepositoryException;

        /**
         * Checks whether the given path is in the range of the release root. This does not check whether the resource actually exists.
         *
         * @param path an absolute path
         * @return true if it's within the tree spanned by the release root.
         */
        boolean appliesToPath(@Nullable String path);

        /**
         * Maps the relative path to the absolute path ( {@link #getReleaseRoot()} + '/' + relativePath ) ; if it's
         * already an absolute path into the release this returns it unmodified. If it's a path into the interal
         * release store, it's transformed to the absolute path into the content that corresponds to that path.
         * If it does not belong to the release at all, an {@link IllegalArgumentException} is thrown. For an empty
         * path, the release root is returned.
         */
        @Nonnull
        String absolutePath(@Nullable String path) throws IllegalArgumentException;

        /** Compares the releaseRoot and releaseNode paths. */
        @Override
        boolean equals(Object o);

        /** Returns information about the activation of a versionable at relativePath. */
        @Nullable
        VersionReference versionReference(@Nullable String relativePath);
    }

    /**
     * Is thrown when a release label given as argument is not found for a release root.
     * This is a runtime exception since this is not something to be expected - that'd be a weird race condition or
     * an UI error.
     */
    class ReleaseNotFoundException extends RuntimeException {
        public ReleaseNotFoundException() {
            // empty
        }

        public ReleaseNotFoundException(String msg) {
            super(msg);
        }
    }

    /**
     * Is thrown when there is no release root for a versionable.
     * This is a runtime exception since this is not something to be expected - an UI error or broken content.
     * The release root is the ancestor with a {@link StagingConstants#TYPE_MIX_RELEASE_ROOT}.
     */
    class ReleaseRootNotFoundException extends RuntimeException {
        public ReleaseRootNotFoundException(String path) {
            super("Could not find a release root containing " + path);
        }
    }

    /** Is thrown when a release label given as argument is not found for a release root. */
    class ReleaseExistsException extends Exception {
        public ReleaseExistsException(Resource releaseRoot, String releaseNumber) {
            super("Release already exists: " + releaseNumber + " for " + releaseRoot.getPath());
        }
    }

    /** Is thrown when a user tries to modify a {@link Release#isClosed()} release. */
    class ReleaseClosedException extends Exception {
    }

    /** Is thrown when a release should be deleted that's has {@link Release#getMarks()}. */
    class ReleaseProtectedException extends Exception {
    }

}

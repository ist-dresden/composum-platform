package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.staging.*;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service to view / manage which documents are put into a release - mostly for {@link PlatformVersionsServlet}.
 *
 * @see PlatformVersionsServlet
 */
public interface PlatformVersionsService {

    /**
     * A {@link ResourceFilter} that filters things that can be activated (that is, is versionable itself or contains a
     * content node that can be activated).
     */
    ResourceFilter ACTIVATABLE_FILTER = ResourceFilter.FilterSet.Rule.or.of(
            new ResourceFilter.TypeFilter(CoreConstants.MIX_VERSIONABLE),
            new ResourceFilter.ContentNodeFilter(false, ResourceFilter.ALL,
                    new ResourceFilter.TypeFilter(CoreConstants.MIX_VERSIONABLE)
            )
    );

    /** Specifies the state of a versionable wrt. a specific release. */
    enum ActivationState {
        /** New, not activated (not in release and has never been there). */
        initial,
        /** Activated and not modified (in release). */
        activated,
        /**
         * Activated but modified since last activation, or deleted in the release content.
         * As modification count both a changed {@link com.composum.sling.core.util.CoreConstants#PROP_LAST_MODIFIED}
         * wrt. to the last activation, as well as a different version.
         */
        modified,
        /** Deactivated (originally present but later removed ({@link #deactivate(String, List)}) from release. */
        deactivated,
        /** When comparing the workspace to a release: deleted from the workspace. */
        deleted
    }

    /** Information about the status of a versionable in the workspace wrt. a release, or of a versionable within a release wrt. a previous release. */
    interface Status {

        String getPath();

        /** Calculated modification status. */
        @NotNull
        ActivationState getActivationState();

        /**
         * The time the versionable was last modified in the workspace.
         *
         * @return 'last modified' or 'created'
         */
        @Nullable
        Calendar getLastModified();

        @Nullable
        String getLastModifiedBy();

        /**
         * The latest version reference - when comparing workspace to release, this is in the release; when
         * comparing release to release, this is in {@link #getNextRelease()}. Might be null in case of {@link ActivationState#initial}.
         */
        @Nullable
        VersionReference getVersionReference();

        /**
         * The release we compare from. If we compare the workspace, this is null.
         */
        @Nullable
        Release getNextRelease();

        /**
         * The detail information about the versionable as it is in the workspace / the release if we're comparing a release with a previous release.
         * This is null if the status refers to a deleted versionable.
         */
        @Nullable
        ReleasedVersionable getNextVersionable();

        /**
         * The release {@link #getPreviousVersionable()} is about (see there). When comparing workspace to
         * release this is not null,
         * when comparing release to release this might be null (if there was no previous release).
         */
        @Nullable
        Release getPreviousRelease();

        /**
         * The detail information about the versionable within the release if the status is about the workspace / within the previous release if it's comparing two releases. This is null if the versionable is {@link ActivationState#initial} /
         * if the previous release did not contain the versionable at all.
         */
        @Nullable
        ReleasedVersionable getPreviousVersionable();

        /** The workspace resource (versionable) that corresponds to the released stuff. */
        @Nullable
        Resource getWorkspaceResource();

    }

    /**
     * Returns the release number of the default release - currently the
     * {@value com.composum.sling.platform.staging.StagingConstants#CURRENT_RELEASE} release.
     * This is used in many of the other methods if the release isn't given explicitly.
     */
    @NotNull
    Release getDefaultRelease(@NotNull Resource versionable);

    /**
     * Returns the status for the versionable comparing the workspace to the given or {@link #getDefaultRelease(Resource)} release.
     * Some non-obvious edge cases:
     *
     * @param versionable the versionable in the workspace
     * @param releaseKey  the key for the release; if null we take the {@link #getDefaultRelease(Resource)}
     * @return the status or null if this isn't applicable because there is no release root or the resource is not a versionable.
     */
    @Nullable
    Status getStatus(@NotNull Resource versionable, @Nullable String releaseKey)
            throws RepositoryException;

    /**
     * Puts the latest content (or a specified version) of the document into the release.
     * If no version is given, we automatically {@link javax.jcr.version.VersionManager#checkpoint(String)} the versionable
     * if it was modified, and use the latest version of it.
     * <p>
     * Caution: since this may do a checkpoint, it needs a clean resolver before calling.
     *
     * @param releaseKey  a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionable the path to a versionable. In case of the "activation" of a deletion, this can be {@link org.apache.sling.api.resource.NonExistingResource}. In this case we use the path to find it in the release.
     * @param versionUuid optionally, a previous version of the document that is
     * @return information about the activation
     */
    @NotNull
    ActivationResult activate(@Nullable String releaseKey, @NotNull Resource versionable, @Nullable String versionUuid)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException;

    /**
     * Puts the latest content of a couple of documents into the release.
     * We automatically {@link javax.jcr.version.VersionManager#checkpoint(String)} each versionables
     * if it was modified, and use the latest version of it.
     * <p>
     * Caution: since this may do a checkpoint, it needs a clean resolver before calling.
     *
     * @param releaseKey   a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionables a number of versionables which are possibly checkpointed and then activated simultaneously
     * @return information about the activation
     */
    @NotNull
    ActivationResult activate(@Nullable String releaseKey, @NotNull List<Resource> versionables)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException;

    /**
     * Sets the versionables to "deactivated" - it is marked as not present in the release anymore.
     *
     * @param releaseKey   a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionables ist of versionables to revert
     */
    void deactivate(@Nullable String releaseKey, @NotNull List<Resource> versionables)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException;

    /**
     * Reverts a number of versionables to the state they were in the previous release
     * (in the sense of {@link com.composum.sling.platform.staging.ReleaseNumberCreator#COMPARATOR_RELEASES}).
     * If there is no previous release, this deletes the versionable from the content (the "previous release" counting as empty in this case).
     *
     * @param resolver         a resolver we can use
     * @param releaseKey       a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionablePaths list of paths to versionables to revert. We use paths instead of resources since they might not exist in the workspace (if moved or deleted)
     *                         nor in the StagingResolver (if deactivated)
     * @return information about the activation
     */
    @NotNull
    ActivationResult revert(@NotNull ResourceResolver resolver, @Nullable String releaseKey, @NotNull List<String> versionablePaths)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException;

    /** Deletes old versions of the versionable - only versions in releases and after the last version which is in a release are kept. */
    void purgeVersions(@NotNull Resource versionable)
            throws RepositoryException;

    /**
     * Returns a {@link ResourceFilter} that accepts resources contained in a release. Outside of the release root it just takes the current contet.
     *
     * @param resourceInRelease some resource below a release root, used to find the release root
     * @param releaseKey        a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param releaseMapper     a {@link ReleaseMapper} that determines what is taken from the release, and what from the current content
     * @param additionalFilter  a resource filter that has additionally be matched for the resource within the release (and is executed with the Staging Resolver).
     *                          If null, we just use {@link ResourceFilter#ALL}.
     * @return a {@link ResourceFilter} that returns true for resources contained in the release
     */
    @NotNull
    ResourceFilter releaseAsResourceFilter(@NotNull Resource resourceInRelease, @Nullable String releaseKey,
                                           @Nullable ReleaseMapper releaseMapper, @Nullable ResourceFilter additionalFilter);

    /**
     * Returns description of versionables which are changed in a release in comparision to the release before.
     */
    @NotNull
    List<Status> findReleaseChanges(@NotNull final Release release) throws RepositoryException;

    /**
     * Returns description of versionables which are changed in the workspace in comparision to the release.
     */
    @NotNull
    List<Status> findWorkspaceChanges(@NotNull final Release release) throws RepositoryException;

    /** Can be used to inform the user about the results of an activation. */
    class ActivationResult {
        @Nullable
        private transient final Release release;
        @NotNull
        private final Map<String, SiblingOrderUpdateStrategy.Result> changedPathsInfo;
        @NotNull
        private final Set<String> newPaths;
        @NotNull
        private final Map<String, String> movedPaths;
        @NotNull
        private final Set<String> removedPaths;

        /**
         * Constructor for which the sets / maps are modified later.
         */
        public ActivationResult(@Nullable Release release) {
            this(release, null, null, null, null);
        }

        /**
         * Constructor that immediately sets everything.
         */
        public ActivationResult(@Nullable Release release, @Nullable Map<String, SiblingOrderUpdateStrategy.Result> changedPathsInfo,
                                @Nullable Set<String> newPaths, @Nullable Map<String, String> movedPaths, @Nullable Set<String> removedPaths) {
            this.release = release;
            this.changedPathsInfo = changedPathsInfo != null ? changedPathsInfo : new HashMap<>();
            this.newPaths = newPaths != null ? newPaths : new HashSet<>();
            this.movedPaths = movedPaths != null ? movedPaths : new HashMap<>();
            this.removedPaths = removedPaths != null ? removedPaths : new HashSet<>();
        }

        @NotNull
        public ActivationResult merge(@NotNull ActivationResult other) {
            if (release != null && !release.equals(other.getRelease())) {
                throw new IllegalArgumentException("Merging results for different releases.");
            }
            Release newRelease = release != null ? release : other.getRelease();
            Map<String, String> moved = new HashMap<>();
            moved.putAll(getMovedPaths());
            moved.putAll(other.getMovedPaths());
            LinkedHashSet<String> combinedNewPaths = new LinkedHashSet<>(getNewPaths());
            combinedNewPaths.addAll(other.newPaths);
            LinkedHashSet<String> combinedRemovedPaths = new LinkedHashSet<>(getRemovedPaths());
            combinedRemovedPaths.addAll(other.getRemovedPaths());
            return new ActivationResult(newRelease,
                    SiblingOrderUpdateStrategy.Result.combine(changedPathsInfo, other.getChangedPathsInfo()),
                    combinedNewPaths,
                    moved,
                    combinedRemovedPaths
            );
        }

        /** A map with paths where we changed the order of children in the release. */
        @NotNull
        public Map<String, SiblingOrderUpdateStrategy.Result> getChangedPathsInfo() {
            return changedPathsInfo;
        }

        @Nullable
        public Release getRelease() {
            return release;
        }

        /** If as result of an operation there is a new item in a release, this contains its path. */
        @NotNull
        public Set<String> getNewPaths() {
            return newPaths;
        }

        /** If an item was moved in a release according to the operation, this maps the absolute old path to the absolute new path. */
        @NotNull
        public Map<String, String> getMovedPaths() {
            return movedPaths;
        }

        /** If as result of an operation an item vanished in a release, this contains its path. */
        @NotNull
        public Set<String> getRemovedPaths() {
            return removedPaths;
        }

        @NotNull
        @Override
        public String toString() {
            return "ActivationResult(" + changedPathsInfo + ")";
        }
    }

}

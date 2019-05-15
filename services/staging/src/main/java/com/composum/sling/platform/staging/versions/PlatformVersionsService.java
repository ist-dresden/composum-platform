package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    public static final ResourceFilter ACTIVATABLE_FILTER = ResourceFilter.FilterSet.Rule.or.of(
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
        /** Deactivated (originally present but later removed ({@link #deactivate(String, Resource)}) from release. */
        deactivated
    }

    /** Information about the status of a versionable wrt. a release. */
    interface Status {

        /** The state; null if the resource is not in a release tee - this isn't applicable in that case. */
        @Nullable
        ActivationState getActivationState();

        @Nullable
        Calendar getLastActivated();

        @Nullable
        String getLastActivatedBy();

        /**
         * The time the versionable was last modified in the workspace.
         *
         * @return 'last modified' or 'created'
         */
        @Nonnull
        Calendar getLastModified();

        @Nonnull
        String getLastModifiedBy();

        @Nullable
        Calendar getLastDeactivated();

        @Nullable
        String getLastDeactivatedBy();

        /**
         * The release this is relative to. Please note that in the case of {@link ActivationState#initial} the versionable
         * does not need to be in the release.
         *
         * @return the release or null if the resource is not within a release tree
         */
        @Nullable
        StagingReleaseManager.Release release();

        /** The detail information about the versionable within the release. This is null if the versionable is {@link ActivationState#initial}. */
        @Nullable
        ReleasedVersionable releaseVersionableInfo();

        /** The detail information about the versionable as it is in the workspace. This is null if the status refers to a deleted versionable. */
        @Nullable
        ReleasedVersionable currentVersionableInfo();
    }

    /**
     * Returns the release number of the default release - currently the
     * {@value com.composum.sling.platform.staging.StagingConstants#CURRENT_RELEASE} release.
     * This is used in many of the other methods if the release isn't given explicitly.
     */
    @Nonnull
    StagingReleaseManager.Release getDefaultRelease(@Nonnull Resource versionable);

    /**
     * Returns the status for the versionable for the given or {@link #getDefaultRelease(Resource)} release.
     *
     * @return the status or null if this isn't applicable because there is no release root or the resource is not a versionable.
     */
    @Nullable
    Status getStatus(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    /**
     * Puts the latest content (or a specified version) of the document into the release.
     * If no version is given, we automatically {@link javax.jcr.version.VersionManager#checkpoint(String)} the versionable
     * if it was modified, and use the latest version of it.
     * <p>
     * Caution: since this may do a checkpoint, it needs a clean resolver before calling.
     *
     * @param releaseKey  a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionable the path to a versionable
     * @param versionUuid optionally, a previous version of the document that is
     * @return information about the activation
     */
    @Nonnull
    ActivationResult activate(@Nullable String releaseKey, @Nonnull Resource versionable, @Nullable String versionUuid)
            throws PersistenceException, RepositoryException;

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
    @Nonnull
    ActivationResult activate(@Nullable String releaseKey, @Nonnull List<Resource> versionables)
            throws PersistenceException, RepositoryException;

    /**
     * Sets the versionables to "deactivated" - it is marked as not present in the release anymore.
     *
     * @param releaseKey   a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionables ist of versionables to revert
     */
    void deactivate(@Nullable String releaseKey, @Nonnull List<Resource> versionables)
            throws PersistenceException, RepositoryException;

    /**
     * Reverts a document to the state it was in the previous release (in the sense of {@link com.composum.sling.platform.staging.ReleaseNumberCreator#COMPARATOR_RELEASES}).
     *
     * @param releaseKey  a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionable the path to a versionable
     */
    void revert(@Nullable String releaseKey, @Nonnull Resource versionable)
            throws PersistenceException, RepositoryException;

    /**
     * Reverts a number of versionables to the state they were in the previous release
     * (in the sense of {@link com.composum.sling.platform.staging.ReleaseNumberCreator#COMPARATOR_RELEASES}).
     *
     * @param releaseKey   a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionables ist of versionables to revert
     */
    void revert(@Nullable String releaseKey, @Nonnull List<Resource> versionables)
            throws PersistenceException, RepositoryException;

    /** Deletes old versions of the versionable - only versions in releases and after the last version which is in a release are kept. */
    void purgeVersions(@Nonnull Resource versionable)
            throws PersistenceException, RepositoryException;

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
    @Nonnull
    ResourceFilter releaseAsResourceFilter(@Nonnull Resource resourceInRelease, @Nullable String releaseKey,
                                           @Nullable ReleaseMapper releaseMapper, @Nullable ResourceFilter additionalFilter);

    /** Can be used to inform the user about the results of an activation. */
    class ActivationResult {
        private final Map<String, SiblingOrderUpdateStrategy.Result> changedPathsInfo;

        public ActivationResult(@Nullable Map<String, SiblingOrderUpdateStrategy.Result> changedPathsInfo) {
            this.changedPathsInfo = changedPathsInfo != null ? changedPathsInfo : Collections.emptyMap();
        }

        public ActivationResult merge(ActivationResult other) {
            return new ActivationResult(SiblingOrderUpdateStrategy.Result.combine(changedPathsInfo, other.getChangedPathsInfo()));
        }

        /** A map with paths where we changed the order of children in the release. */
        @Nonnull
        public Map<String, SiblingOrderUpdateStrategy.Result> getChangedPathsInfo() {
            return changedPathsInfo;
        }

        @Override
        public String toString() {
            return "ActivationResult(" + changedPathsInfo + ")";
        }
    }

}

package com.composum.sling.platform.staging.versions;

import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.Calendar;
import java.util.Map;

/**
 * Service to view / manage which documents are put into a release - mostly for {@link PlatformVersionsServlet}.
 *
 * @see PlatformVersionsServlet
 */
public interface PlatformVersionsService {

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
        /** Deactivated (originally present but later removed ({@link #deactivate(Resource, String)}) from release. */
        deactivated
    }

    /** Information about the status of a versionable wrt. a release. */
    interface Status {

        @Nonnull
        ActivationState getActivationState();

        @Nullable
        Calendar getLastActivated();

        @Nullable
        String getLastActivatedBy();

        /**
         * The time the versionable was last modified in the workspace.
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
         */
        @Nonnull
        StagingReleaseManager.Release release();

        /** The detail information about the versionable within the release. This is null if the versionable is {@link ActivationState#initial}. */
        @Nullable
        ReleasedVersionable releaseVersionableInfo();

        /** The detail information about the versionable as it is in the workspace. This is null if the status refers to a deleted versionable. */
        @Nullable
        ReleasedVersionable currentVersionableInfo();
    }

    /**
     * Returns the release number of the default release - marked as preview, if that isn't present the release marked as public,
     * if that isn't present the {@value com.composum.sling.platform.staging.StagingConstants#CURRENT_RELEASE} release.
     * This is used in many of the other methods if the release isn't given explicitly.
     */
    @Nonnull
    StagingReleaseManager.Release getDefaultRelease(@Nonnull Resource versionable);

    /** Returns the status for the versionable for the given or {@link #getDefaultRelease(Resource)} release. */
    @Nonnull
    Status getStatus(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    /**
     * Puts the latest content (or a specified version) of the document into the release.
     * If no version is given, we automatically {@link javax.jcr.version.VersionManager#checkpoint(String)} the versionable
     * if it was modified, and use the latest version of it.
     *
     * @param versionable the path to a versionable
     * @param releaseKey  a release number or null for the {@link #getDefaultRelease(Resource)}.
     * @param versionUuid optionally, a previous version of the document that is
     * @throws PersistenceException
     * @throws RepositoryException
     * @return a map with paths where we changed the order of children in the release.
     */
    Map<String, SiblingOrderUpdateStrategy.Result> activate(@Nonnull Resource versionable, @Nullable String releaseKey, @Nullable String versionUuid)
            throws PersistenceException, RepositoryException;

    /** Sets the document to "deactivated" - it is marked as not present in the release anymore. */
    void deactivate(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    /** Deletes old versions of the versionable - only versions in releases and after the last version which is in a release are kept. */
    void purgeVersions(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

}

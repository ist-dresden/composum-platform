package com.composum.sling.platform.staging.versions;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.Calendar;

/**
 * this or a similar service is needed...
 */
public interface PlatformVersionsService {

    enum ActivationState {
        initial,    // new, not activated (not in release)
        activated,  // activated and not modified (in release)
        modified,   // activated but modified since last activation
        deactivated // deactivated (removed from release)
    }

    interface Status {

        @Nonnull
        ActivationState getActivationState();

        @Nullable
        Calendar getLastActivated();

        @Nullable
        String getLastActivatedBy();

        /**
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
    }

    Status getStatus(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    void activate(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    void deactivate(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;

    void purgeVersions(@Nonnull Resource versionable, @Nullable String releaseKey)
            throws PersistenceException, RepositoryException;
}

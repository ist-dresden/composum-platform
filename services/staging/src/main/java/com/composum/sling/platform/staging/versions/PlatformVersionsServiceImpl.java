package com.composum.sling.platform.staging.versions;

import com.composum.sling.platform.security.AccessMode;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;

/**
 * This is the default implementation of the {@link PlatformVersionsService} - see there.
 *
 * @see PlatformVersionsService
 */
@Component(
        service = {PlatformVersionsService.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Versions Service"
        }
)
public class PlatformVersionsServiceImpl implements PlatformVersionsService {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformVersionsServiceImpl.class);

    @Reference
    protected StagingReleaseManager releaseManager;

    @Nonnull
    @Override
    public StagingReleaseManager.Release getDefaultRelease(@Nonnull Resource versionable) {
        StagingReleaseManager.Release release = releaseManager.findReleaseByMark(versionable, AccessMode.ACCESS_MODE_PREVIEW.toLowerCase());
        if (release == null)
            release = releaseManager.findReleaseByMark(versionable, AccessMode.ACCESS_MODE_PUBLIC.toLowerCase());
        if (release == null)
            release = releaseManager.findRelease(versionable, StagingConstants.CURRENT_RELEASE);
        return release;
    }

    @Override
    public Status getStatus(@Nonnull Resource versionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        LOG.error("PlatformVersionsServiceImpl.getStatus");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: PlatformVersionsServiceImpl.getStatus");
        // FIXME hps 2019-04-26 implement PlatformVersionsServiceImpl.getStatus
        Status result = null;
        return result;
    }

    @Override
    public void activate(@Nonnull Resource versionable, @Nullable String releaseKey, @Nullable String versionUuid) throws PersistenceException, RepositoryException {
        LOG.error("PlatformVersionsServiceImpl.activate");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: PlatformVersionsServiceImpl.activate");
        // FIXME hps 2019-04-26 implement PlatformVersionsServiceImpl.activate

    }

    @Override
    public void deactivate(@Nonnull Resource versionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        LOG.error("PlatformVersionsServiceImpl.deactivate");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: PlatformVersionsServiceImpl.deactivate");
        // FIXME hps 2019-04-26 implement PlatformVersionsServiceImpl.deactivate

    }

    @Override
    public void purgeVersions(@Nonnull Resource versionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        LOG.error("PlatformVersionsServiceImpl.purgeVersions");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: PlatformVersionsServiceImpl.purgeVersions");
        // FIXME hps 2019-04-26 what shall that do?
    }
}

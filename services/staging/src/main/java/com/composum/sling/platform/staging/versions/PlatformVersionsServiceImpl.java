package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.security.AccessMode;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
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
import java.util.Calendar;
import java.util.Map;

import static com.composum.sling.core.util.CoreConstants.CONTENT_NODE;
import static com.composum.sling.core.util.CoreConstants.TYPE_VERSIONABLE;
import static com.composum.sling.core.util.SlingResourceUtil.getPath;

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

    public static final String PROP_LAST_ACTIVATED = "lastActivated";
    public static final String PROP_LAST_ACTIVATED_BY = "lastActivatedBy";
    public static final String PROP_LAST_DEACTIVATED = "lastDeactivated";
    public static final String PROP_LAST_DEACTIVATED_BY = "lastDeactivatedBy";

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

    protected StagingReleaseManager.Release getRelease(Resource versionable, String releaseKey) {
        if (StringUtils.isBlank(releaseKey))
            return getDefaultRelease(versionable);
        return releaseManager.findRelease(versionable, releaseKey);
    }

    /**
     * If the versionable is a {@link com.composum.sling.core.util.CoreConstants#TYPE_VERSIONABLE} it is returned,
     * else we check whether it has a versionable {@link com.composum.sling.core.util.CoreConstants#CONTENT_NODE} and return that,
     * otherwise we throw up as this is an invalid argument.
     */
    protected ResourceHandle normalizeVersionable(Resource versionable) {
        ResourceHandle handle = ResourceHandle.use(versionable);
        if (handle.isValid() && handle.isOfType(TYPE_VERSIONABLE))
            return handle;
        ResourceHandle contentResource = handle.getContentResource();
        if (contentResource.isValid() && contentResource.isOfType(TYPE_VERSIONABLE))
            return contentResource;
        throw new IllegalArgumentException("Not a versionable nor something with a versionable " + CONTENT_NODE + " : " + getPath(versionable));
    }

    @Override
    public StatusImpl getStatus(@Nonnull Resource rawVersionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        StagingReleaseManager.Release release = getRelease(versionable, releaseKey);
        ReleasedVersionable current = ReleasedVersionable.forBaseVersion(versionable);
        ReleasedVersionable released = releaseManager.listReleaseContents(release).stream()
                .filter(rv -> rv.getVersionHistory().equals(current.getVersionHistory()))
                .findAny().orElse(null);
        return new StatusImpl(release, current, released);
    }

    @Override
    public Map<String, SiblingOrderUpdateStrategy.Result> activate(@Nonnull Resource rawVersionable, @Nullable String releaseKey, @Nullable String versionUuid) throws PersistenceException, RepositoryException {
        LOG.info("Requested activation {} in {} to {}", getPath(rawVersionable), releaseKey, versionUuid);
        Map<String, SiblingOrderUpdateStrategy.Result> result = null;
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        // XXX(hps,2019-04-29) possibly checkpoint document
        StatusImpl status = getStatus(versionable, releaseKey);
        if (status.getActivationState() != ActivationState.activated) {
            ReleasedVersionable releasedVersionable = status.currentVersionableInfo();
            if (StringUtils.isNotBlank(versionUuid) && !StringUtils.equals(versionUuid, releasedVersionable.getVersionUuid())) {
                releasedVersionable = status.releaseVersionableInfo(); // make sure we don't move the document around
                releasedVersionable.setVersionUuid(versionUuid);
            }
            releasedVersionable.setActive(true);
            result = releaseManager.updateRelease(status.release(), releasedVersionable);
            status = getStatus(versionable, releaseKey);
            Validate.isTrue(status.getVersionReference().isValid());
            status.getVersionReference().setProperty(PROP_LAST_ACTIVATED, Calendar.getInstance());
            status.getVersionReference().setProperty(PROP_LAST_ACTIVATED_BY, rawVersionable.getResourceResolver().getUserID());
            LOG.info("Activated {} in {} to {}", getPath(rawVersionable), status.release().getNumber(), status.currentVersionableInfo().getVersionUuid());
        } else {
            LOG.info("Already activated in {} : {}", status.release().getNumber(), getPath(rawVersionable));
        }
        return result;
    }

    @Override
    public void deactivate(@Nonnull Resource versionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        LOG.info("Requested deactivation {} in {}", getPath(versionable), releaseKey);
        StatusImpl status = (StatusImpl) getStatus(versionable, releaseKey);
        switch (status.getActivationState()) {
            case modified:
            case activated:
                LOG.info("Deactivating in " + status.release().getNumber() + " : " + versionable);
                ReleasedVersionable releasedVersionable = status.releaseVersionableInfo();
                releasedVersionable.setActive(false);
                releaseManager.updateRelease(status.release(), releasedVersionable);
                status.getVersionReference().setProperty(PROP_LAST_DEACTIVATED, Calendar.getInstance());
                status.getVersionReference().setProperty(PROP_LAST_DEACTIVATED_BY, versionable.getResourceResolver().getUserID());
            case initial:
            case deactivated:
                LOG.info("Not deactivating in " + status.release().getNumber() + " since not active: " + versionable);
            default:
        }
    }

    @Override
    public void purgeVersions(@Nonnull Resource versionable, @Nullable String releaseKey) throws PersistenceException, RepositoryException {
        LOG.error("PlatformVersionsServiceImpl.purgeVersions");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: PlatformVersionsServiceImpl.purgeVersions");
        // FIXME hps 2019-04-26 what shall that do?
    }

    protected static class StatusImpl implements Status {
        @Nonnull
        private final StagingReleaseManager.Release release;
        @Nullable
        private final ReleasedVersionable current;
        @Nullable
        private final ReleasedVersionable released;
        @Nonnull // may be invalid, though
        private final ResourceHandle currentResource;
        @Nonnull // may be invalid, though
        private final ResourceHandle releasedVersionReference;

        public StatusImpl(@Nonnull StagingReleaseManager.Release release, @Nullable ReleasedVersionable current, @Nullable ReleasedVersionable released) {
            this.release = release;
            this.current = current;
            this.released = released;
            currentResource = ResourceHandle.use(release.getReleaseRoot().getChild(current.getRelativePath()));
            if (current != null && !currentResource.isValid())
                throw new IllegalArgumentException("Invalid current resource " + release + " - " + current);
            releasedVersionReference = ResourceHandle.use(release.getMetaDataNode().getChild("../" + StagingConstants.NODE_RELEASE_ROOT).getChild(current.getRelativePath()));
            if (releasedVersionReference != null && !releasedVersionReference.isValid())
                throw new IllegalArgumentException("Invalid releasedVersionReference " + release + " - " + releasedVersionReference);
        }

        @Nonnull
        @Override
        public ActivationState getActivationState() {
            if (released == null)
                return ActivationState.initial;
            if (!released.getActive())
                return ActivationState.deactivated;
            if (!StringUtils.equals(current.getVersionUuid(), released.getVersionUuid()))
                return ActivationState.modified;
            if (getLastActivated() == null ||
                    getLastModified() != null && getLastModified().after(getLastActivated()))
                return ActivationState.modified;
            return ActivationState.activated;
        }

        @Nullable
        @Override
        public Calendar getLastActivated() {
            return releasedVersionReference.getProperty(PROP_LAST_ACTIVATED, Calendar.class);
        }

        @Nullable
        @Override
        public String getLastActivatedBy() {
            return releasedVersionReference.getProperty(PROP_LAST_ACTIVATED_BY, String.class);
        }

        @Nonnull
        @Override
        public Calendar getLastModified() {
            Calendar result = null;
            if (currentResource.isValid()) {
                result = currentResource.getProperty(CoreConstants.JCR_LASTMODIFIED, Calendar.class);
                if (result == null) {
                    result = currentResource.getProperty(CoreConstants.JCR_CREATED, Calendar.class);
                }
            }
            return result;
        }

        @Nonnull
        @Override
        public String getLastModifiedBy() {
            String result = null;
            if (currentResource.isValid()) {
                result = currentResource.getProperty(CoreConstants.JCR_LASTMODIFIED_BY, String.class);
                if (StringUtils.isBlank(result)) {
                    result = currentResource.getProperty(CoreConstants.JCR_CREATED_BY, String.class);
                }
            }
            return result;
        }

        @Nullable
        @Override
        public Calendar getLastDeactivated() {
            return releasedVersionReference.getProperty(PROP_LAST_DEACTIVATED, Calendar.class);
        }

        @Nullable
        @Override
        public String getLastDeactivatedBy() {
            return releasedVersionReference.getProperty(PROP_LAST_DEACTIVATED_BY, String.class);
        }

        @Nonnull
        @Override
        public StagingReleaseManager.Release release() {
            return release;
        }

        @Nullable
        @Override
        public ReleasedVersionable releaseVersionableInfo() {
            return released;
        }

        @Nonnull
        @Override
        public ReleasedVersionable currentVersionableInfo() {
            return current;
        }

        /** The version reference currently in the release. Might be invalid. */
        @Nonnull
        public ResourceHandle getVersionReference() {
            return releasedVersionReference;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("release", release)
                    .append("released", released)
                    .append("current", current)
                    .toString();
        }

    }

}

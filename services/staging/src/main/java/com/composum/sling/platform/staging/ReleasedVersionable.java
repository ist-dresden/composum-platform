package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.version.Version;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Objects;

import static com.composum.sling.core.util.SlingResourceUtil.getPath;

/** Describes the state of a versionable in a release. Can also be used as parameter object to update the release. */
public class ReleasedVersionable implements Serializable, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(ReleasedVersionable.class);

    /** @see #getRelativePath() */
    @Nonnull
    private String relativePath;

    /** @see #getVersionableUuid() */
    @Nonnull
    private String versionableUuid;

    /** @see #getVersionUuid() */
    @Nullable
    private String versionUuid;

    /** @see #getVersionHistory() */
    @Nonnull
    private String versionHistory;

    /** @see #isActive() */
    private boolean active;

    /** Creates a {@link ReleasedVersionable} that corresponds to the base version of the given versionable. */
    public static ReleasedVersionable forBaseVersion(@Nonnull Resource resource) {
        if (!ResourceUtil.isResourceType(Objects.requireNonNull(resource), ResourceUtil.TYPE_VERSIONABLE))
            throw new IllegalArgumentException("resource is not versionable: " + getPath(resource));
        ReleasedVersionable result = new ReleasedVersionable();
        Resource releaseRoot = resource;
        StringBuilder relPath = new StringBuilder();
        while (releaseRoot != null && !ResourceUtil.isResourceType(releaseRoot, StagingConstants.TYPE_MIX_RELEASE_ROOT)) {
            if (relPath.length() > 0) relPath.insert(0, '/');
            relPath.insert(0, releaseRoot.getName());
            releaseRoot = releaseRoot.getParent();
        }
        if (releaseRoot == null)
            throw new IllegalArgumentException("Could not find release root for " + getPath(resource));
        result.setRelativePath(relPath.toString());
        result.setActive(true);
        result.setVersionableUuid(resource.getValueMap().get(ResourceUtil.PROP_UUID, String.class));
        result.setVersionUuid(resource.getValueMap().get(JcrConstants.JCR_BASEVERSION, String.class));
        result.setVersionHistory(resource.getValueMap().get(JcrConstants.JCR_VERSIONHISTORY, String.class));
        return result;
    }

    /** Releasemanager internal: creates a {@link ReleasedVersionable} that corresponds to a {@link StagingConstants#TYPE_VERSIONREFERENCE}. */
    public static ReleasedVersionable fromVersionReference(@Nonnull Resource releaseWorkspaceCopyRoot, @Nonnull Resource resource) {
        if (!ResourceUtil.isResourceType(resource, StagingConstants.TYPE_VERSIONREFERENCE)) {
            throw new IllegalArgumentException("resource is not version reference: " + getPath(resource));
        }
        if (!SlingResourceUtil.isSameOrDescendant(releaseWorkspaceCopyRoot, resource)) {
            throw new IllegalArgumentException("Resource not in treeroot: " + resource.getPath() + ", " + releaseWorkspaceCopyRoot.getPath());
        }

        ReleasedVersionable result = new ReleasedVersionable();
        ResourceHandle rh = ResourceHandle.use(resource);

        result.setActive(!rh.getProperty(StagingConstants.PROP_DEACTIVATED, Boolean.FALSE));
        result.setVersionableUuid(rh.getProperty(StagingConstants.PROP_VERSIONABLEUUID, String.class));
        result.setVersionUuid(rh.getProperty(StagingConstants.PROP_VERSION, String.class));
        result.setVersionHistory(rh.getProperty(StagingConstants.PROP_VERSIONHISTORY, String.class));
        result.setRelativePath(StringUtils.removeStart(resource.getPath().substring(releaseWorkspaceCopyRoot.getPath().length()), "/"));

        return result;
    }

    /** Path relative to release root. */
    @Nonnull
    public String getRelativePath() {
        return relativePath;
    }

    /** @see #getRelativePath() */
    public void setRelativePath(@Nonnull String relativePath) {
        this.relativePath = relativePath;
    }

    /** {@value com.composum.sling.core.util.ResourceUtil#PROP_UUID} of the versionable that was put into the release. */
    @Nonnull
    public String getVersionableUuid() {
        return versionableUuid;
    }

    /** @see #getVersionableUuid() */
    public ReleasedVersionable setVersionableUuid(@Nonnull String versionableUuid) {
        this.versionableUuid = versionableUuid;
        return this;
    }

    /**
     * {@link Version#getUUID()} of the version of the versionable that is in the release / is to be put into the release.
     * If something needs to be removed, this can be {@link #setVersionableUuid(String)} to null.
     */
    @Nullable
    public String getVersionUuid() {
        return versionUuid;
    }

    /** @see #getVersionUuid() */
    public ReleasedVersionable setVersionUuid(@Nullable String versionUuid) {
        this.versionUuid = versionUuid;
        return this;
    }

    /** The UUID of the version history, as unchangeable identifier. */
    @Nonnull
    public String getVersionHistory() {
        return versionHistory;
    }

    /** @see #getVersionHistory() */
    public ReleasedVersionable setVersionHistory(@Nonnull String versionHistory) {
        this.versionHistory = versionHistory;
        return this;
    }

    /** Whether the versionable is active in the release. */
    public boolean isActive() {
        return active;
    }

    /** @see #isActive() */
    public ReleasedVersionable setActive(boolean active) {
        this.active = active;
        return this;
    }

    /**
     * Releasemanager internal: writes values into a versionreference.
     *
     * @deprecated only for ReleaseManager internal use, public for technical reasons.
     */
    @Deprecated
    public void writeToVersionReference(@Nonnull Resource workspaceCopyRoot, @Nonnull Resource versionReference) throws RepositoryException {
        ResourceHandle rh = ResourceHandle.use(versionReference);
        if (!StagingConstants.TYPE_VERSIONREFERENCE.equals(rh.getPrimaryType())) {
            throw new IllegalArgumentException("Not a version reference: " + SlingResourceUtil.getPath(versionReference));
        }
        String oldVersionHistory = rh.getProperty(StagingConstants.PROP_VERSIONHISTORY);
        if (oldVersionHistory != null && !oldVersionHistory.equals(getVersionHistory())) {
            LOG.warn("Writing to different versionhistory: {} written to {}", this.toString(), ReleasedVersionable.fromVersionReference(workspaceCopyRoot, versionReference));
            clearVersionableProperties(versionReference);
        }
        rh.setProperty(StagingConstants.PROP_DEACTIVATED, !isActive());
        rh.setProperty(StagingConstants.PROP_VERSIONABLEUUID, getVersionableUuid(), PropertyType.WEAKREFERENCE);
        rh.setProperty(StagingConstants.PROP_VERSION, getVersionUuid(), PropertyType.REFERENCE);
        rh.setProperty(StagingConstants.PROP_VERSIONHISTORY, getVersionHistory(), PropertyType.REFERENCE);
        if (isActive()) {
            rh.setProperty(StagingConstants.PROP_LAST_ACTIVATED, Calendar.getInstance());
            rh.setProperty(StagingConstants.PROP_LAST_ACTIVATED_BY, versionReference.getResourceResolver().getUserID());
        } else {
            rh.setProperty(StagingConstants.PROP_LAST_DEACTIVATED, Calendar.getInstance());
            rh.setProperty(StagingConstants.PROP_LAST_DEACTIVATED_BY, versionReference.getResourceResolver().getUserID());
        }
    }

    protected void clearVersionableProperties(Resource versionReference) {
        ModifiableValueMap modvm = versionReference.adaptTo(ModifiableValueMap.class);
        for (String key : new ArrayList<>(modvm.keySet())) {
            if (!ResourceUtil.PROP_PRIMARY_TYPE.equals(key)) {
                try {
                    modvm.remove(key);
                } catch (Exception e) {
                    LOG.warn("Could not remove property {} from {}", key, SlingResourceUtil.getPath(versionReference));
                }
            }
        }
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

    @Override
    public ReleasedVersionable clone() {
        try {
            return (ReleasedVersionable) super.clone();
        } catch (CloneNotSupportedException e) { // can't happen - we just want to get rid of the unneccesary throws declaration
            throw new IllegalStateException(e);
        }
    }

    /** Compares all fields. */
    @SuppressWarnings({"OverlyComplexBooleanExpression", "ObjectEquality"})
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReleasedVersionable that = (ReleasedVersionable) o;
        return isActive() == that.isActive() &&
                Objects.equals(getRelativePath(), that.getRelativePath()) &&
                Objects.equals(getVersionableUuid(), that.getVersionableUuid()) &&
                Objects.equals(getVersionUuid(), that.getVersionUuid()) &&
                getVersionHistory().equals(that.getVersionHistory());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getVersionHistory());
    }
}

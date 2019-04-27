package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.version.Version;

/** Describes the state of a versionable in a release. Can also be used as parameter object to update the release. */
public class ReleasedVersionable {

    /** @see #getRelativePath() */
    @Nonnull
    private String relativePath;

    /** Path relative to release root. */
    @Nonnull
    public String getRelativePath() {
        return relativePath;
    }

    /** @see #getRelativePath() */
    public void setRelativePath(@Nonnull String relativePath) {
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
    @Nonnull
    private String versionUuid;

    /** {@link Version#getUUID()} of the version of the versionable that is in the release / is to be put into the release.. */
    @Nonnull
    public String getVersionUuid() {
        return versionUuid;
    }

    /** @see #getVersionUuid() */
    public void setVersionUuid(@Nonnull String versionUuid) {
        this.versionUuid = versionUuid;
    }

    /** @see #getVersionHistory() */
    @Nonnull
    private String versionHistory;

    /** The UUID of the version history, as unchangeable identifier. */
    @Nonnull
    public String getVersionHistory() {
        return versionHistory;
    }

    /** @see #getVersionHistory() */
    public void setVersionHistory(@Nonnull String versionHistory) {
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
        while (!ResourceUtil.isResourceType(releaseRoot, StagingConstants.TYPE_MIX_RELEASE_ROOT)) {
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

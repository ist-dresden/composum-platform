package com.composum.sling.platform.staging.replication.json;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;

public class VersionableInfo {

    private static final Logger LOG = LoggerFactory.getLogger(VersionableInfo.class);

    private String path;
    private String version;

    /**
     * Constructor for JSON deserialization.
     *
     * @deprecated please use {@link #of(Resource, String)} to construct.
     */
    @Deprecated
    public VersionableInfo() {
        // empty
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    /**
     * Fills a VersionableInfo with data from the given resource.
     *
     * @param resource       the resource to use it with.
     * @param relativeToPath if this is set, we assume all resurces are at or below this and only set their relative
     *                       path.
     */
    @Nullable
    public static VersionableInfo of(@Nonnull Resource resource, @Nullable String relativeToPath) {
        if (ResourceUtil.isNodeType(resource, ResourceUtil.TYPE_VERSIONABLE)) {
            String version = resource.getValueMap().get(StagingConstants.PROP_REPLICATED_VERSION, String.class);
            if (StringUtils.isNotBlank(version)) {
                VersionableInfo info = new VersionableInfo();
                String path = resource.getPath();
                if (StringUtils.isNotBlank(relativeToPath)) {
                    if (!SlingResourceUtil.isSameOrDescendant(relativeToPath, path)) {
                        throw new IllegalArgumentException("Not subpath of " + relativeToPath + " : " + path);
                    }
                    path = "/" + SlingResourceUtil.relativePath(relativeToPath, path);
                }
                info.path = path;
                info.version = version;
                return info;
            } else { // that shouldn't happen in the intended usecase.
                LOG.warn("Something's wrong here: {} has no {}", resource.getPath(), PROP_REPLICATED_VERSION);
            }
        } else if (ResourceUtil.CONTENT_NODE.equals(resource.getName())) {
            // that shouldn't happen in the intended usecase.
            LOG.warn("Something's wrong here: {} has no {}", resource.getPath(), PROP_REPLICATED_VERSION);
        }
        return null;
    }


    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("path", path)
                .append("version", version)
                .toString();
    }
}

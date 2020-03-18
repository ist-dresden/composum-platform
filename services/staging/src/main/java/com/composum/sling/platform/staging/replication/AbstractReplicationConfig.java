package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.security.AccessMode;

import javax.annotation.Nonnull;

/**
 * Base class with common properties for replication configurations, be that inplace or remote.
 */
public abstract class AbstractReplicationConfig extends AbstractSlingBean implements ReplicationConfig {
    /**
     * Property name for {@link #getUrl()}.
     */
    public static final String PROP_URL = "targetUrl";
    /**
     * Property name for {@link #getProxyKey()}.
     */
    public static final String PROP_PROXY_KEY = "proxyKey";

    /**
     * Optional human-readable description.
     */
    @Override
    public String getDescription() {
        return getProperty(ResourceUtil.PROP_DESCRIPTION, String.class);
    }

    /**
     * The release mark (mostly {@link com.composum.sling.platform.security.AccessMode#PUBLIC} /
     * {@link com.composum.sling.platform.security.AccessMode#PREVIEW}) for which the release is replicated.
     * If empty, there is no replication.
     */
    @Nonnull
    @Override
    public String getStage() {
        return getProperty(PN_STAGE, String.class);
    }

    /**
     * Whether this replication is enabled - default true.
     */
    @Override
    public boolean isEnabled() {
        return getProperty(PN_IS_ENABLED, Boolean.TRUE);
    }

    @Override
    public boolean isEditable() {
        return getProperty(PN_IS_EDITABLE, true);
    }

    /**
     * Optional, the path we replicate - must be the site or a subpath of the site.
     */
    @Nonnull
    @Override
    public String getSourcePath() {
        return getProperty(PN_SOURCE_PATH, String.class);
    }

    /**
     * Optional, the path we replicate to. If not given, this is equivalent to the source Path.
     */
    @Override
    public String getTargetPath() {
        return getProperty(PN_TARGET_PATH, String.class);
    }

    @Nonnull
    @Override
    public String getConfigResourceType() {
        return getProperty(ResourceUtil.PROP_RESOURCE_TYPE, String.class);
    }
}

package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.security.AccessMode;
import org.apache.sling.api.resource.Resource;

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

    protected String description;
    protected String stage;
    protected Boolean enabled;
    protected String sourcePath;
    protected String targetPath;
    protected String configResourceType;

    @Override
    public void initialize(BeanContext context, Resource resource) {
        // we initialize everything right now since this object might live longer than the resolver.
        super.initialize(context, resource);
        this.description = getProperty(ResourceUtil.PROP_DESCRIPTION, String.class);
        this.stage = getProperty(PN_STAGE, String.class);
        this.enabled = getProperty(PN_IS_ENABLED, Boolean.TRUE);
        this.sourcePath = getProperty(PN_SOURCE_PATH, String.class);
        this.targetPath = getProperty(PN_TARGET_PATH, String.class);
        this.configResourceType = getProperty(ResourceUtil.PROP_RESOURCE_TYPE, String.class);
    }

    /**
     * Optional human-readable description.
     */
    @Override
    public String getDescription() {
        return description;
    }

    /**
     * The release mark (mostly {@link com.composum.sling.platform.security.AccessMode#PUBLIC} /
     * {@link com.composum.sling.platform.security.AccessMode#PREVIEW}) for which the release is replicated.
     * If empty, there is no replication.
     */
    @Nonnull
    @Override
    public String getStage() {
        return stage;
    }

    /**
     * Whether this replication is enabled - default true.
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Optional, the path we replicate - must be the site or a subpath of the site.
     */
    @Nonnull
    @Override
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Optional, the path we replicate to. If not given, this is equivalent to the source Path.
     */
    @Override
    public String getTargetPath() {
        return targetPath;
    }

}

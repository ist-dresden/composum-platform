package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.nodes.NodesConfiguration;
import com.composum.platform.commons.request.AccessMode;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.replication.AbstractReplicationConfig;
import com.composum.sling.platform.staging.replication.AbstractReplicationService;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationType;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend;
import com.composum.sling.platform.staging.replication.inplace.InPlacePublisherService.InPlaceReleasePublishingProcess;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;

/**
 * Replicates a release by copying it from the staging resolver to another path on the same host.
 */
@Component(
        service = ReleaseChangeEventListener.class,
        property = {Constants.SERVICE_DESCRIPTION + "=Composum Platform In-Place Publisher Service"},
        immediate = true
)
@Designate(ocd = InPlacePublisherService.Configuration.class)
public class InPlacePublisherService
        extends AbstractReplicationService<InPlaceReplicationConfig, InPlaceReleasePublishingProcess> {

    private static final Logger LOG = LoggerFactory.getLogger(InPlacePublisherService.class);

    /**
     * Name of the threadpool, and thus prefix for the thread names.
     */
    public static final String THREADPOOL_NAME = "RCInplRepl";

    /**
     * {@link ReleaseChangeProcess#getType()} for this kind of replication.
     */
    public static final String TYPE_INPLACE = "inplace";

    protected volatile Configuration config;

    @Reference
    private StagingReleaseManager releaseManager;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private PublicationReceiverBackend backend;

    @Reference
    protected NodesConfiguration nodesConfig;

    @Reference
    protected ThreadPoolManager threadPoolManager;

    protected volatile ThreadPool threadPool;

    @Activate
    @Modified
    protected void activate(final Configuration theConfig) {
        LOG.info("activated");
        this.config = theConfig;
        if (theConfig != null && theConfig.enabled()) {
            this.threadPool = threadPoolManager.get(THREADPOOL_NAME);
        } else {
            releaseThreadpool();
        }
    }

    @Override
    @Deactivate
    protected void deactivate() throws IOException {
        LOG.info("deactivated");
        this.config = null;
        try {
            super.deactivate();
        } finally {
            releaseThreadpool();
        }
    }

    protected void releaseThreadpool() {
        ThreadPool oldThreadPool = this.threadPool;
        this.threadPool = null;
        if (oldThreadPool != null) {
            threadPoolManager.release(oldThreadPool);
        }
    }

    @Nonnull
    @Override
    protected InPlaceReleasePublishingProcess makePublishingProcess(Resource releaseRoot, InPlaceReplicationConfig replicationConfig) {
        return new InPlaceReleasePublishingProcess(releaseRoot, replicationConfig);
    }

    @Nonnull
    @Override
    protected List<InPlaceReplicationConfig> getReplicationConfigs(@Nonnull Resource releaseRoot, @Nonnull BeanContext context) {
        Configuration theConfig = config;
        if (theConfig == null || !theConfig.enabled()) {
            return Collections.emptyList();
        }
        List<InPlaceReplicationConfig> configs = super.getReplicationConfigs(releaseRoot, context);
        if (configs.isEmpty() && !StringUtils.isAllBlank(theConfig.inPlacePreviewPath(), theConfig.inPlacePublicPath()) &&
                PUBLIC_MODE_IN_PLACE.equals(ResourceHandle.use(releaseRoot).getContentProperty(PROP_PUBLIC_MODE, DEFAULT_PUBLIC_MODE))) {
            configs = new ArrayList<>();
            if (!SlingResourceUtil.isSameOrDescendant(config.contentPath(), releaseRoot.getPath())) {
                LOG.warn("Releaseroot is not in content path: {}", releaseRoot.getPath());
                return Collections.emptyList();
            }
            String relativeContentPath = SlingResourceUtil.relativePath(config.contentPath(), releaseRoot.getPath());
            if (StringUtils.isNotBlank(config.inPlacePreviewPath())) {
                String stage = AccessMode.ACCESS_MODE_PREVIEW.toLowerCase();
                configs.add(new InPlaceReplicationConfig.ImplicitInPlaceReplicationConfig(
                        SlingResourceUtil.appendPaths(getConfigParent(releaseRoot.getPath()), "/cpl:implicit/" + stage),
                        releaseRoot.getPath(), stage,
                        SlingResourceUtil.appendPaths(config.inPlacePreviewPath(), relativeContentPath)
                ));
            }
            if (StringUtils.isNotBlank(config.inPlacePublicPath())) {
                String stage = AccessMode.ACCESS_MODE_PUBLIC.toLowerCase();
                configs.add(new InPlaceReplicationConfig.ImplicitInPlaceReplicationConfig(
                        SlingResourceUtil.appendPaths(getConfigParent(releaseRoot.getPath()), "/cpl:implicit/" + stage),
                        releaseRoot.getPath(), stage,
                        SlingResourceUtil.appendPaths(config.inPlacePublicPath(), relativeContentPath)
                ));
            }
        }
        return configs;
    }

    @Nonnull
    @Override
    protected Class<InPlaceReplicationConfig> getReplicationConfigClass() {
        return InPlaceReplicationConfig.class;
    }

    @Nonnull
    @Override
    protected ReplicationType getReplicationType() {
        return InPlaceReplicationConfig.INPLACE_REPLICATION_TYPE;
    }

    @Override
    protected boolean isEnabled() {
        Configuration theconfig = this.config;
        boolean enabled = theconfig != null && theconfig.enabled();
        if (!enabled) {
            processesCache.clear();
        }
        return enabled;
    }

    @Override
    protected StagingReleaseManager getReleaseManager() {
        return releaseManager;
    }

    @Override
    protected ResourceResolverFactory getResolverFactory() {
        return resolverFactory;
    }

    protected class InPlaceReleasePublishingProcess extends AbstractReplicationProcess
            implements ReleaseChangeProcess {

        protected InPlaceReleasePublishingProcess(@Nonnull Resource releaseRoot, InPlaceReplicationConfig replicationConfig) {
            super(releaseRoot, replicationConfig);
        }

        @Nonnull
        @Override
        protected PublicationReceiverFacade createTargetFacade(@Nonnull AbstractReplicationConfig replicationConfig, @Nonnull BeanContext context) {
            return new InPlacePublicationReceiverFacade((InPlaceReplicationConfig) replicationConfig, context,
                    () -> config, backend, resolverFactory, nodesConfig, threadPool);
        }

        @Override
        public String getType() {
            return TYPE_INPLACE;
        }

        @Override
        public boolean isImplicit() {
            return cachedConfig.isImplicit();
        }

    }

    @ObjectClassDefinition(
            name = "Composum Platform In-Place Publisher Service Configuration",
            description = "Configures a service that publishes release changes to another path in the local system"
    )
    public @interface Configuration {

        @AttributeDefinition(
                description = "the general on/off switch for this service: whether in-place replication is allowed"
        )
        boolean enabled() default true;

        @AttributeDefinition(
                name = "InPlace Preview path",
                description = "the repository root of the 'preview' replication content; default '/preview'"
        )
        String inPlacePreviewPath() default "/preview";

        @AttributeDefinition(
                name = "InPlace Public path",
                description = "the repository root of the 'public' replication content; default '/public'"
        )
        String inPlacePublicPath() default "/public";

        @AttributeDefinition(
                name = "Content path",
                description = "the repository root of the authoring content to replicate; default '/content'"
        )
        String contentPath() default "/content";

    }

}

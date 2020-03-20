package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.core.BeanContext;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.replication.AbstractReplicationConfig;
import com.composum.sling.platform.staging.replication.AbstractReplicationService;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationType;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.composum.sling.platform.staging.replication.inplace.InPlacePublisherService.InPlaceReleasePublishingProcess;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Replicates a release by copying it from the staging resolver to another path on the same host.
 */
@Component(
        service = ReleaseChangeEventListener.class,
        property = {Constants.SERVICE_DESCRIPTION + "=Composum Platform In-Place Publisher Service"},
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        immediate = true
)
@Designate(ocd = InPlacePublisherService.Configuration.class)
public class InPlacePublisherService
        extends AbstractReplicationService<InPlaceReplicationConfig, InPlaceReleasePublishingProcess> {

    private static final Logger LOG = LoggerFactory.getLogger(InPlacePublisherService.class);

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

    @Activate
    @Modified
    protected void activate(final Configuration theConfig) {
        LOG.info("activated");
        this.config = theConfig;
    }

    @Nonnull
    @Override
    protected InPlaceReleasePublishingProcess makePublishingProcess(Resource releaseRoot, InPlaceReplicationConfig replicationConfig) {
        return new InPlaceReleasePublishingProcess(releaseRoot, replicationConfig);
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
    @Deactivate
    protected void deactivate() throws IOException {
        LOG.info("deactivated");
        this.config = null;
        super.deactivate();
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
            return new InPlacePublicationReceiverFacade((InPlaceReplicationConfig) replicationConfig, context, () -> config, backend);
        }

        @Override
        public String getType() {
            return TYPE_INPLACE;
        }

    }

    @ObjectClassDefinition(
            name = "Composum Platform In-Place Publisher Service Configuration",
            description = "Configures a service that publishes release changes to another path in the local system"
    )
    public @interface Configuration {

        @AttributeDefinition(
                description = "the general on/off switch for this service"
        )
        boolean enabled() default false;

    }

}

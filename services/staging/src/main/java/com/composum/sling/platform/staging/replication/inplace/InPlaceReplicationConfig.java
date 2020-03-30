package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.core.BeanContext;
import com.composum.sling.platform.staging.replication.AbstractReplicationConfig;
import com.composum.sling.platform.staging.replication.ReplicationConfig;
import com.composum.sling.platform.staging.replication.ReplicationType;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bean modeling a inplace publication configuration for use with the replication mechanism. The resource is a subnode below /conf/{sitepath}/{site}/replication/ .
 */
public class InPlaceReplicationConfig extends AbstractReplicationConfig {
    private static final Logger LOG = LoggerFactory.getLogger(InPlaceReplicationConfig.class);

    public static final ReplicationType INPLACE_REPLICATION_TYPE = new InplaceReplicationType();

    @Nonnull
    @Override
    public ReplicationType getReplicationType() {
        return INPLACE_REPLICATION_TYPE;
    }

    /**
     * Whether this is an implicit configuration that is used in the absence of any explicitly configured replications.
     */
    @Override
    public boolean isImplicit() {
        return false;
    }

    public static class ImplicitInPlaceReplicationConfig implements ReplicationConfig {

        protected final String releaseRoot;
        protected final String stage;
        protected final String targetPath;

        public ImplicitInPlaceReplicationConfig(@Nonnull String releaseRoot, @Nonnull String stage, @Nonnull String targetPath) {
            this.releaseRoot = releaseRoot;
            this.stage = stage;
            this.targetPath = targetPath;
        }

        @Nonnull
        @Override
        public String getStage() {
            return stage;
        }

        @Nonnull
        @Override
        public String getTitle() {
            return "Implicit replication";
        }

        @Nullable
        @Override
        public String getDescription() {
            return "An implicitly configured replication used in the absence of any explicitly configured replications.";
        }

        @Override
        public String getPath() {
            return null;
        }

        @Nonnull
        @Override
        public String getSourcePath() {
            return releaseRoot;
        }

        @Nullable
        @Override
        public String getTargetPath() {
            return targetPath;
        }

        @Nonnull
        @Override
        public ReplicationType getReplicationType() {
            return INPLACE_REPLICATION_TYPE;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isEditable() {
            return false;
        }

        @Nonnull
        @Override
        public String getConfigResourceType() {
            return null;
        }
    }

}

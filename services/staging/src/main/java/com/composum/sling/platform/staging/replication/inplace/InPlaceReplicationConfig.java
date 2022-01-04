package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.core.BeanContext;
import com.composum.sling.platform.staging.replication.AbstractReplicationConfig;
import com.composum.sling.platform.staging.replication.ReplicationConfig;
import com.composum.sling.platform.staging.replication.ReplicationType;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bean modeling a inplace publication configuration for use with the replication mechanism. The resource is a subnode below /conf/{sitepath}/{site}/replication/ .
 */
public class InPlaceReplicationConfig extends AbstractReplicationConfig {
    private static final Logger LOG = LoggerFactory.getLogger(InPlaceReplicationConfig.class);

    public static final ReplicationType INPLACE_REPLICATION_TYPE = new InplaceReplicationType();

    @NotNull
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

    /**
     * Special class for implicitly configured in-place replications.
     */
    public static class ImplicitInPlaceReplicationConfig extends InPlaceReplicationConfig {

        protected final String path;
        protected final String releaseRoot;
        protected final String stage;
        protected final String targetPath;

        public ImplicitInPlaceReplicationConfig(@NotNull String path, @NotNull String releaseRoot, @NotNull String stage, @NotNull String targetPath) {
            this.releaseRoot = releaseRoot;
            this.stage = stage;
            this.targetPath = targetPath;
            this.path = path;
        }

        @Override
        public void initialize(BeanContext context, Resource resource) {
            // not initialized from a resource
        }

        @NotNull
        @Override
        public String getStage() {
            return stage;
        }

        @NotNull
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
            return path;
        }

        @NotNull
        @Override
        public String getSourcePath() {
            return releaseRoot;
        }

        @Nullable
        @Override
        public String getTargetPath() {
            return targetPath;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isEditable() {
            return false;
        }

        @Override
        public boolean isImplicit() {
            return true;
        }

        @NotNull
        @Override
        public String getConfigResourceType() {
            return null;
        }


        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ImplicitInPlaceReplicationConfig{");
            sb.append("path='").append(path).append('\'');
            sb.append(", releaseRoot='").append(releaseRoot).append('\'');
            sb.append(", stage='").append(stage).append('\'');
            sb.append(", targetPath='").append(targetPath).append('\'');
            sb.append('}');
            return sb.toString();
        }
    }

}

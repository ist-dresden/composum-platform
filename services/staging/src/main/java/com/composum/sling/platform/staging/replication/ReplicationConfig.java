package com.composum.sling.platform.staging.replication;

import com.composum.sling.platform.staging.replication.ReplicationType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The configuration necessary to carry out a replication.
 */
public interface ReplicationConfig {

    String PN_STAGE = "stage";
    String PN_SOURCE_PATH = "sourcePath";
    String PN_TARGET_PATH = "targetPath";
    String PN_REPLICATION_TYPE = "replicationType";
    String PN_IS_ENABLED = "enabled";
    String PN_IS_EDITABLE = "editable";

    @NotNull
    String getStage();

    @NotNull
    String getTitle();

    @Nullable
    String getDescription();

    /**
     * @return the path of the configuration resource itself
     */
    String getPath();

    /**
     * @return the path of the content affected by this configuration
     */
    @NotNull
    String getSourcePath();

    /**
     * @return the optional path at the target (for reference transformation)
     */
    @Nullable
    public String getTargetPath();

    /**
     * @return the replication service type (implementation type)
     */
    @NotNull
    ReplicationType getReplicationType();

    /**
     * @return the resource type of the component to view / edit the configuration
     */
    @NotNull
    String getConfigResourceType();

    /**
     * @return 'true' if the replication declared by this configuration is enabled
     */
    boolean isEnabled();

    /**
     * If true, this is a "second class" configuration: it is implicitly present if there is no explicit configuration
     * done. Those will only be used if there is no explicit configuration of any type - an explicit configuration overrides any
     * implicit configuration.
     */
    default boolean isImplicit() {
        return false;
    }

    /**
     * @return 'true' if the configuration can be changed by the user
     */
    boolean isEditable();
}

package com.composum.sling.platform.staging.replication;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides metadata including a description for a particular replication implementation.
 */
public interface ReplicationType {

    /**
     * @return a unique key of the replication implementation
     */
    @NotNull
    String getServiceId();

    /**
     * @return a short readable identifier of the replication implementation (i18n)
     */
    @NotNull
    String getTitle();

    /**
     * @return a description of the replication implementation purpose and details (i18n)
     */
    @Nullable
    String getDescription();

    /**
     * @return the resource type of the replication implementation configuration component
     */
    @NotNull
    String getResourceType();
}

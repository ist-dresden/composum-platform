package com.composum.sling.platform.staging;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/** Service that creates {@link ReplicationService.ReleaseChangeEvent}s and distributes them among the {@link ReplicationService}s. */
public interface ReplicationServicePublisher {

    /**
     * Creates an {@link ReplicationService.ReleaseChangeEvent} and publishes it among the
     * {@link ReplicationService}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    public void publishActivation(@Nullable ReplicationService.ReleaseChangeEvent event)
            throws ReplicationService.ReplicationFailedException;

}

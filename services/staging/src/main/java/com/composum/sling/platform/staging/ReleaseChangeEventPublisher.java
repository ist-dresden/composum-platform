package com.composum.sling.platform.staging;

import javax.annotation.Nullable;

/** Service that creates {@link ReleaseChangeEventListener.ReleaseChangeEvent}s and distributes them among the {@link ReleaseChangeEventListener}s. */
public interface ReleaseChangeEventPublisher {

    /**
     * Creates an {@link ReleaseChangeEventListener.ReleaseChangeEvent} and publishes it among the
     * {@link ReleaseChangeEventListener}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    public void publishActivation(@Nullable ReleaseChangeEventListener.ReleaseChangeEvent event)
            throws ReleaseChangeEventListener.ReplicationFailedException;

}

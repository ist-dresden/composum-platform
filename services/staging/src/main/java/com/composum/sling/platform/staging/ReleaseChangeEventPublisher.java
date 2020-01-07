package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nullable;
import java.util.Collection;

/** Service that distributes {@link ReleaseChangeEvent}s among the {@link ReleaseChangeEventListener}s. */
public interface ReleaseChangeEventPublisher {

    /**
     * Creates an {@link ReleaseChangeEvent} and publishes it among the
     * {@link ReleaseChangeEventListener}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    void publishActivation(@Nullable ReleaseChangeEvent event)
            throws ReleaseChangeEventListener.ReplicationFailedException;

    Collection<ReleaseChangeProcess> processesFor(Release release);

    Collection<ReleaseChangeProcess> processesFor(Resource releaseRoot);

}

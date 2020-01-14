package com.composum.sling.platform.staging;

import com.composum.platform.commons.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/** Service that distributes {@link ReleaseChangeEvent}s among the {@link ReleaseChangeEventListener}s. */
public interface ReleaseChangeEventPublisher {

    /**
     * Creates an {@link ReleaseChangeEvent} and publishes it among the
     * {@link ReleaseChangeEventListener}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    void publishActivation(@Nullable ReleaseChangeEvent event)
            throws ReleaseChangeEventListener.ReplicationFailedException;

    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Release release);

    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Resource releaseRoot);

    @Nonnull
    Map<String, ReplicationStateInfo> replicationState(@Nullable Resource releaseRoot);

    @Nullable
    AggregatedReplicationStateInfo aggregatedReplicationState(@Nullable Resource releaseRoot);

    /** Information about one {@link ReleaseChangeProcess} useable for JSON serialization. */
    class ReplicationStateInfo {
        /** An unique id for the {@link ReleaseChangeProcess}. */
        public String id;
        public String name;
        public String description;
        public ReleaseChangeProcess.ReleaseChangeProcessorState state;
        public Long startedAt;
        public Long finishedAt;
        public MessageContainer messages;
        /** Whether the remote release change number is equal to the local one. */
        public Boolean isSynchronized;
        public boolean enabled;
    }

    /**
     * General information about the {@link ReleaseChangeProcess}es of a release root, useable for JSON
     * serialization.
     */
    class AggregatedReplicationStateInfo {
        /** True if for all enabled {@link ReleaseChangeProcess} the remote content is equivalent to the local one. */
        public boolean everythingIsSynchronized;
        /** True if there are still some replications running. */
        public boolean replicationsAreRunning;
        /** True if there are some {@link ReleaseChangeProcess}es in error state. */
        public boolean haveErrors;
        /** Number of enabled {@link ReleaseChangeProcess}es. */
        public int numberEnabledProcesses;
    }

}

package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/** Service that distributes {@link ReleaseChangeEvent}s among the {@link ReleaseChangeEventListener}s. */
public interface ReleaseChangeEventPublisher {

    /**
     * Creates an {@link ReleaseChangeEvent} and publishes it among the
     * {@link ReleaseChangeEventListener}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    void publishActivation(@Nullable ReleaseChangeEvent event)
            throws ReleaseChangeEventListener.ReplicationFailedException;

    /** Returns all {@link ReleaseChangeProcess} that apply to the given release. */
    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Release release);

    /** Returns all {@link ReleaseChangeProcess} for the given release root. */
    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Resource releaseRoot);

    /** Returns details on the current states of the {@link ReleaseChangeProcess}es for the given release root. */
    @Nonnull
    Map<String, ReplicationStateInfo> replicationState(@Nullable Resource releaseRoot);

    /** Returns an aggregated view of the stati of the {@link ReleaseChangeProcess}es for the given release root. */
    @Nullable
    AggregatedReplicationStateInfo aggregatedReplicationState(@Nullable Resource releaseRoot);

    /**
     * Compares the contents of the whole release root or subtree at {resource}.
     *
     * @param resource        a release root or a subnode of it
     * @param returnDetails   if true, details about the differences are returned
     * @param processIdParams if set, comparisons are only done for the {@link ReleaseChangeProcess} with the given
     *                        {@link ReleaseChangeProcess#getId()}.
     * @param output          the result is added here: the {@link ReleaseChangeProcess#getId()} is mapped to the
     *                        corresponding {@link CompareResult}.
     */
    void compareTree(@Nonnull ResourceHandle resource, boolean returnDetails, @Nullable String[] processIdParams,
                     @Nonnull Map<String, Object> output) throws ReleaseChangeEventListener.ReplicationFailedException;

    /** Information about one {@link ReleaseChangeProcess} used for JSON serialization. */
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
        /** Time of last (successful) replication, as in {@link System#currentTimeMillis()}. */
        public Long lastReplicationTimestamp;
    }

    /**
     * General information about the {@link ReleaseChangeProcess}es of a release root, used for JSON
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

    /** Result object for {@link #compareTree(ResourceHandle, boolean, String[], Map)}. */
    class CompareResult {
        /** Summary: if true, all things are equal. If false - see other attributes for details. */
        public boolean equal;
        public boolean releaseChangeNumbersEqual;
        public int newVersionableCount;
        public int updatedVersionableCount;
        public int removedVersionableCount;
        /** Number of parent nodes of versionables with different attributes. */
        public int changedParentNodeCount;
        /** Number of children orderings of nodes with ordered children that are different. */
        public int changedChildrenOrderCount;

        /** Checks whether {@link #equal} should be set. {@link #equal} must be set to the result of this. */
        public boolean calculateEqual() {
            return releaseChangeNumbersEqual && newVersionableCount == 0 && updatedVersionableCount == 0 && removedVersionableCount == 0
                    && removedVersionableCount == 0 && changedParentNodeCount == 0 && changedChildrenOrderCount == 0;
        }

        /** Only if details are wanted: the paths of the new versionables not present on the remote system. */
        public String[] newVersionables;
        /** Only if details are wanted: the paths of versionables different than on the remote system. */
        public String[] updatedVersionables;
        /** Only if details are wanted: the paths of versionables not present on the local system. */
        public String[] removedVersionables;
        /** Only if details are wanted: the paths of parent nodes of versionables with different attributes. */
        public String[] changedParentNodes;
        /**
         * Only if details are wanted: the paths of parent nodes of versionables with orderable children and a
         * different child order.
         */
        public String[] changedChildrenOrders;
    }

}

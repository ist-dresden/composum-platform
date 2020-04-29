package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.replication.ReplicationException;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Service that distributes {@link ReleaseChangeEvent}s among the {@link ReleaseChangeEventListener}s.
 * It basically aggregates all {@link ReleaseChangeEventListener}s.
 */
public interface ReleaseChangeEventPublisher {

    /**
     * Creates an {@link ReleaseChangeEvent} and publishes it among the
     * {@link ReleaseChangeEventListener}s - see the event for documentation of the parameters, null will be transformed to empty lists.
     */
    void publishActivation(@Nullable ReleaseChangeEvent event)
            throws ReleaseChangeEventListener.ReplicationFailedException;

    /**
     * Returns all {@link ReleaseChangeProcess} that apply to the given release.
     *
     * @param releaseRoot the root of a release - if null we return an empty collection.
     * @param stage       if set, this filters the result after {@link ReleaseChangeProcess#getStage()}.
     */
    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Release release, @Nullable String stage);

    /**
     * Returns all {@link ReleaseChangeProcess} for the given release root.
     *
     * @param releaseRoot the root of a release - if null we return an empty collection.
     * @param stage       if set, this filters the result after {@link ReleaseChangeProcess#getStage()}.
     */
    @Nonnull
    Collection<ReleaseChangeProcess> processesFor(@Nullable Resource releaseRoot, @Nullable String stage);

    /**
     * Returns details on the current states of the {@link ReleaseChangeProcess}es for the given release root.
     *
     * @param releaseRoot the root of a release - if null we return an empty map.
     * @param stage       if set, this filters the result after {@link ReleaseChangeProcess#getStage()}.
     */
    @Nonnull
    Map<String, ReplicationStateInfo> replicationState(@Nullable Resource releaseRoot, @Nullable String stage);

    /**
     * Returns an aggregated view of the stati of the {@link ReleaseChangeProcess}es for the given release root.
     *
     * @param releaseRoot the root of a release - if null we return an empty map.
     * @param stage       if set, this filters the result after {@link ReleaseChangeProcess#getStage()}.
     */
    @Nullable
    AggregatedReplicationStateInfo aggregatedReplicationState(@Nullable Resource releaseRoot, @Nullable String stage);

    /**
     * Determines the path at which a release is replicated. This is the target path for the replication configuration
     * which contains the site root and replicates the given stage. If this doesn't give an unique result, null is returned.
     */
    @Nullable
    String getStagePath(@Nullable Resource releaseRoot, @Nullable String stage);

    /**
     * Stops each running or faulty replication process - reset and clear for a new synchronization.
     *
     * @param releaseRoot the root of a release - if null we return an empty map.
     * @param stage       if set, this filters the result after {@link ReleaseChangeProcess#getStage()}.
     */
    void abortReplication(@Nullable Resource releaseRoot, @Nullable String stage);

    /**
     * Compares the contents of the whole release root or subtree at {resource}.
     *
     * @param resource        a release root or a subnode of it
     * @param details         If 0, only difference counts are returned. If 1, the different paths are included. For some
     *                        replication types higher values switch on further checks.
     * @param processIdParams if set, comparisons are only done for the {@link ReleaseChangeProcess} with the given
     *                        {@link ReleaseChangeProcess#getId()}.
     * @param output          the result is added here: the {@link ReleaseChangeProcess#getId()} is mapped to the
     *                        corresponding {@link CompareResult}.
     */
    void compareTree(@Nonnull ResourceHandle resource, int details, @Nullable String[] processIdParams,
                     @Nonnull Map<String, ? super CompareResult> output) throws ReplicationException;

    /**
     * Information about one {@link ReleaseChangeProcess} used for JSON serialization.
     */
    class ReplicationStateInfo {
        /**
         * An unique id for the {@link ReleaseChangeProcess}.
         */
        public String id;
        /**
         * If false, the service is disabled by configuration.
         */
        public boolean enabled;
        /**
         * True if it's enabled and there is a matching release.
         */
        public boolean active;
        /**
         * Human readable name / title.
         */
        public String name;
        /**
         * Human readable description of the process.
         */
        public String description;
        /**
         * An identifier for the type of the process - e.g. "Remote" or "In-Place".
         */
        public String type;
        /**
         * The current state the process is in.
         */
        public ReleaseChangeProcess.ReleaseChangeProcessorState state;
        /**
         * Rough estimation how much of the currently queued release changes have been processed.
         */
        public int completionPercentage;
        /**
         * Start of last replication run.
         */
        public Long startedAt;
        /**
         * End of last replication run.
         */
        public Long finishedAt;
        /**
         * Whether the remote release change number is equal to the local one.
         */
        public Boolean isSynchronized;
        /**
         * Time of last (successful) replication, as in {@link System#currentTimeMillis()}.
         */
        public Long lastReplicationTimestamp;
        public MessageContainer messages;

        @Nonnull
        public final Map<ReleaseChangeProcess.ReleaseChangeProcessorState, ReleaseChangeProcess.ReplicationHistoryEntry> history =
                new EnumMap<>(ReleaseChangeProcess.ReleaseChangeProcessorState.class);
    }

    /**
     * General information about the {@link ReleaseChangeProcess}es of a release root, used for JSON
     * serialization.
     */
    class AggregatedReplicationStateInfo {
        /**
         * True if for all enabled {@link ReleaseChangeProcess} the remote content is equivalent to the local one.
         */
        public boolean everythingIsSynchronized;
        /**
         * True if all {@link ReleaseChangeProcess}es are active.
         */
        public boolean allAreActive;
        /**
         * True if there are still some replications running.
         */
        public boolean replicationsAreRunning;
        /**
         * True if there are some {@link ReleaseChangeProcess}es in error state.
         */
        public boolean haveErrors;
        /**
         * Number of enabled {@link ReleaseChangeProcess}es.
         */
        public int numberEnabledProcesses;
    }

    /**
     * Result object for {@link #compareTree(ResourceHandle, boolean, String[], Map)}.
     */
    class CompareResult {
        /**
         * Summary: if true, all things are equal. If false - see other attributes for details.
         */
        public boolean equal;
        public boolean releaseChangeNumbersEqual;
        /**
         * Summarizes new / updated / removed versionables.
         */
        public int differentVersionablesCount;
        /**
         * Number of parent nodes of versionables with different attributes.
         */
        public int changedParentNodeCount;
        /**
         * Number of children orderings of nodes with ordered children that are different.
         */
        public int changedChildrenOrderCount;

        /**
         * Checks whether {@link #equal} should be set. {@link #equal} must be set to the result of this.
         */
        public boolean calculateEqual() {
            return releaseChangeNumbersEqual && differentVersionablesCount == 0
                    && changedParentNodeCount == 0 && changedChildrenOrderCount == 0;
        }

        /**
         * Only if details are wanted: the paths of versionables which are new.
         */
        public String[] differentVersionables;
        /**
         * Only if details are wanted: the paths of parent nodes of versionables with different attributes.
         */
        public String[] changedParentNodes;
        /**
         * Only if details are wanted: the paths of parent nodes of versionables with orderable children and a
         * different child order.
         */
        public String[] changedChildrenOrders;
    }

}

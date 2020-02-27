package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A process or workflow that can be triggered by a
 * {@link ReleaseChangeEvent} which, for instance,
 * replicates the release into a remote system. This is meant for operations that can take some time,
 * as opposed to quick operations that can be done immediately within
 * {@link ReleaseChangeEventListener#receive(ReleaseChangeEvent)}.
 * The processes can be shown in the GUI and checked for their state.
 */
public interface ReleaseChangeProcess extends Runnable {

    /**
     * A string (such as a configuration resource path) that identifies this process uniquely. Not for human
     * consumption.
     */
    String getId();

    String getName();

    String getStage();

    String getDescription();

    /** If this returns true, our scheduler should {@link #run()} the process again soon. */
    default boolean needsReschedule() {
        return false;
    }

    enum ReleaseChangeProcessorState {idle, /** is waiting to be run */ awaiting, processing, success, error, disabled}

    /**
     * Adds the information about the event into an internal queue. Shouldn't throw any exceptions and not do
     * anything risky - the actual processing is done when {@link #run()} is called.
     * If there is anything to do and run should be triggered, the {@link #getState()} should be switched to
     * {@link ReleaseChangeProcessorState#awaiting}. The caller guarantees that {@link #run()} is called soon.
     */
    void triggerProcessing(@Nonnull ReleaseChangeEvent event);

    @Nonnull
    ReleaseChangeProcessorState getState();

    /** True if the process is enabled; if not it's state is also {@link ReleaseChangeProcessorState#disabled}. */
    boolean isEnabled();

    /** True if the process is enabled and there is a matching release. */
    boolean isActive();

    /** Estimation how much of the currently queued release changes have been processed. */
    int getCompletionPercentage();

    /**
     * The time the last processing was started as {@link System#currentTimeMillis()}. A {@link #triggerProcessing(ReleaseChangeEvent)} or
     * external changes of release content might restart the process completely.
     */
    @Nullable
    Long getRunStartedAt();

    /** The time the last processing was finished as {@link System#currentTimeMillis()}. */
    @Nullable
    Long getRunFinished();

    /** Can contain some human readable messages about the last run, e.g. errors. */
    @Nonnull
    MessageContainer getMessages();

    @Nullable
    Long getLastReplicationTimestamp();

    /**
     * Checks whether the remote replication is currently at the same
     * {@link StagingReleaseManager.Release#getChangeNumber()} as the local content.
     * If a remote access is necessary to determine this, the result is cached for a while since this might be
     * called on each request of an editor.
     */
    @Nullable
    Boolean isSynchronized(@Nonnull ResourceResolver resolver);

    /** Forces an update of {@link #isSynchronized()} and {@link #getLastReplicationTimestamp()}. */
    void updateSynchronized();

    /**
     * This method performs the processing.
     * To process the changes, this has to be called externally, e.g. by entering the {@link ReleaseChangeProcess}
     * into a threadpool. Must not be called twice in parallel. If there is nothing in the queue, this
     * should just be a no-op.
     */
    @Override
    void run();

    /**
     * Compares the the tree below resource with the remote system's content and determines whether there are
     * differences.
     *
     * @param resource the release root or a subtree in there
     * @param details  If 0, only difference counts are returned. If 1, the different paths are included. For some
     *                 replication types higher values switch on further checks.
     * @return the differences, or null if not enabled
     */
    @Nullable
    ReleaseChangeEventPublisher.CompareResult compareTree(@Nonnull ResourceHandle resource, int details) throws ReleaseChangeEventListener.ReplicationFailedException;

}

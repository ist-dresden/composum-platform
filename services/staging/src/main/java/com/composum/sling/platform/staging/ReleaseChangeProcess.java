package com.composum.sling.platform.staging;

import com.composum.platform.commons.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A process or workflow that can be triggered by a
 * {@link ReleaseChangeEvent} which, for instance,
 * replicates the release into a remote system. This is meant for operations that can take some time,
 * as opposed to quick operations that can be done immediately within
 * {@link ReleaseChangeEventListener#receive(ReleaseChangeEvent)}.
 * The processes can be shown in the GUI and checked for their state.
 */
public interface ReleaseChangeProcess extends Runnable {

    String getName();

    String getDescription();

    enum ReleaseChangeProcessorState {idle, /** is waiting to be run */ awaiting, processing, success, error}

    /**
     * Adds the information about the event into an internal queue. Shouldn't throw any exceptions and not do
     * anything risky - the actual processing is done when {@link #run()} is called.
     * If there is anything to do and run should be triggered, the {@link #getState()} should be switched to
     * {@link ReleaseChangeProcessorState#awaiting}. The caller guarantees that {@link #run()} is called soon.
     */
    void triggerProcessing(@Nonnull ReleaseChangeEvent event);

    @Nonnull
    ReleaseChangeProcessorState getState();

    /** Estimation how much of the currently queued release changes have been processed. */
    default int getCompletionPercentage() {
        switch (getState()) { // just some default implementation that does something somewhat sensible.
            case processing:
                return 50;
            case success:
                return 100;
            default:
                return 0;
        }
    }

    /**
     * The time the last processing was started. A {@link #triggerProcessing(ReleaseChangeEvent)} or
     * external changes of release content might restart the process completely.
     */
    @Nullable
    Date getRunStartedAt();

    /** The time the last processing was finished. */
    @Nullable
    Date getRunFinished();


    /** Can contain some human readable messages about the last run, e.g. errors. */
    @Nonnull
    MessageContainer getMessages();

    /**
     * This method performs the processing.
     * To process the changes, this has to be called externally, e.g. by entering the {@link ReleaseChangeProcess}
     * into a threadpool. Must not be called twice in parallel. If there is nothing in the queue, this
     * should just be a no-op.
     */
    @Override
    void run();

}

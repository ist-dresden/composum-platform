package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Future;

@Component(
        service = {ReleaseChangeEventPublisher.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Replication Service Publisher"
        }
)
public class ReleaseChangeEventPublisherImpl implements ReleaseChangeEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseChangeEventPublisherImpl.class);

    /** Name of the threadpool, and thus prefix for the thread names. */
    public static final String THREADPOOL_NAME = "RCEventPub";

    /**
     * A time we wait before executing changes to try to make sure the transaction was already comitted.
     * If this is too low, this can lead to consistency problems!
     */
    protected static final long WAIT_FOR_COMMIT_TIME_MS = 10000;

    @Reference
    protected ThreadPoolManager threadPoolManager;

    protected final List<ReleaseChangeEventListener> releaseChangeEventListeners = Collections.synchronizedList(new ArrayList<>());

    protected volatile ThreadPool threadPool;

    /** Object to lock over when changing {@link #runningProcesses} or {@link #queuedProcesses}. */
    protected final Object lock = new Object();

    /**
     * Keeps the results to keep track which processes are currently running.
     * Synchronize {@link #lock} when accessing this!
     */
    protected final Map<ReleaseChangeProcess, Future<?>> runningProcesses = new WeakHashMap<>();

    /**
     * Which processes are running but must be called again because there were new events for them in the meantime.
     * Synchronize {@link #lock} when accessing this!
     */
    protected final Map<ReleaseChangeProcess, Boolean> queuedProcesses = new WeakHashMap<>();

    @Reference(
            service = ReleaseChangeEventListener.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Adding listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        //noinspection ObjectEquality : equality for services not defined
        releaseChangeEventListeners.removeIf(releaseChangeEventListener -> releaseChangeEventListener == listener);
        releaseChangeEventListeners.add(listener);
    }

    protected void removeReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Removing listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        //noinspection ObjectEquality : equality for services not defined
        releaseChangeEventListeners.removeIf(releaseChangeEventListener -> releaseChangeEventListener == listener);
    }

    @Override
    public void publishActivation(ReleaseChangeEventListener.ReleaseChangeEvent event) throws ReleaseChangeEventListener.ReplicationFailedException {
        if (event == null || event.isEmpty()) { return; }
        event.finish();
        LOG.info("publishActivation {}", event);
        ReleaseChangeEventListener.ReplicationFailedException exception = null;
        List<ReleaseChangeEventListener> listeners = new ArrayList<>(this.releaseChangeEventListeners);
        // copy listeners to avoid concurrent modification problems
        for (ReleaseChangeEventListener releaseChangeEventListener : listeners) {
            try {
                releaseChangeEventListener.receive(event);
                LOG.debug("published to {} : {}", releaseChangeEventListener, event);
            } catch (ReleaseChangeEventListener.ReplicationFailedException e) {
                LOG.error("Error publishing to {} the event {}", releaseChangeEventListener, event, e);
                if (exception != null) { e.addSuppressed(e); } else { exception = e; }
            } catch (RuntimeException e) {
                LOG.error("Error publishing to {} the event {}", releaseChangeEventListener, event, e);
                ReleaseChangeEventListener.ReplicationFailedException newException = new ReleaseChangeEventListener.ReplicationFailedException("Error publishing to " + releaseChangeEventListener, e, event);
                if (exception != null) { e.addSuppressed(newException); } else { exception = newException; }
            }
        }
        if (exception != null) { throw exception; }
        // we trigger the processes only afterwards - throwing an exception here rolls back the changes

        Collection<ReleaseChangeProcess> processes = processesFor(event.release());
        for (ReleaseChangeProcess process : processes) {
            if (!process.isEnabled()) { continue; }
            try {
                process.triggerProcessing(event);
                deployProcess(process);
            } catch (RuntimeException | InterruptedException e) {
                LOG.error("Error when triggering process {} for {}", process, event, e);
            }
        }
    }

    /**
     * Make sure {@link ReleaseChangeProcess#run()} is called later. If it's currently running, we queue it so that
     * it'll run after the current run is finished - compare {@link RescheduleWrapper#run()}. We rather call run once
     * too many - it is contractually obliged to do nothing if it hasn't anything to do.
     */
    protected void deployProcess(@Nonnull ReleaseChangeProcess process) throws InterruptedException {
        synchronized (lock) {
            Future<?> future = runningProcesses.get(process);
            if (future != null && future.isDone()) {
                future = null;
                runningProcesses.remove(process);
            }
            if (future == null) {
                future = threadPool.submit(new RescheduleWrapper(process));
                runningProcesses.put(process, future);
            } else { // is scheduled or running - we have to call that again later.
                queuedProcesses.put(process, Boolean.TRUE);
            }
        }
    }

    /** Organizes that a process is rescheduled after being run if that's needed. */
    protected class RescheduleWrapper implements Runnable {

        protected final ReleaseChangeProcess process;

        public RescheduleWrapper(ReleaseChangeProcess process) {
            this.process = process;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(WAIT_FOR_COMMIT_TIME_MS);
                process.run();
            } catch (RuntimeException | InterruptedException e) { // forbidden
                LOG.error("Bug: Process threw exception", e);
            } finally {
                try {
                    synchronized (lock) {
                        boolean queued = queuedProcesses.getOrDefault(process, false);
                        if (queued || process.needsReschedule()) {
                            Future<?> future = threadPool.submit(new RescheduleWrapper(process));
                            runningProcesses.put(process, future);
                            queuedProcesses.remove(process);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.error("Could not reschedule call.", e);
                }
            }
        }
    }

    @Nonnull
    @Override
    public Collection<ReleaseChangeProcess> processesFor(@Nullable StagingReleaseManager.Release release) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
            result.addAll(releaseChangeEventListener.processesFor(release));
        }
        return result;
    }

    @Nonnull
    @Override
    public Collection<ReleaseChangeProcess> processesFor(@Nullable Resource releaseRoot) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
            result.addAll(releaseChangeEventListener.processesFor(releaseRoot));
        }
        return result;
    }

    @Nonnull
    @Override
    public Map<String, ReplicationStateInfo> replicationState(@Nullable Resource releaseRoot) {
        Map<String, ReplicationStateInfo> result = new LinkedHashMap<>();
        for (ReleaseChangeProcess process : processesFor(releaseRoot)) {
            ReplicationStateInfo info = new ReplicationStateInfo();
            info.id = process.getId();
            info.state = process.getState();
            info.name = process.getName();
            info.description = process.getDescription();
            info.startedAt = process.getRunStartedAt();
            info.finishedAt = process.getRunFinished();
            info.messages = process.getMessages();
            info.enabled = process.isEnabled();
            info.active = process.isActive();
            info.completionPercentage = process.getCompletionPercentage();
            process.updateSynchronized();
            info.isSynchronized = process.isSynchronized(releaseRoot.getResourceResolver());
            info.lastReplicationTimestamp = process.getLastReplicationTimestamp();
            result.put(process.getId(), info);
        }
        return result;
    }

    @Nullable
    @Override
    public AggregatedReplicationStateInfo aggregatedReplicationState(@Nullable Resource releaseRoot) {
        if (releaseRoot == null) { return null; }
        AggregatedReplicationStateInfo result = new AggregatedReplicationStateInfo();
        result.replicationsAreRunning = false;
        result.haveErrors = false;
        result.everythingIsSynchronized = true;
        result.numberEnabledProcesses = 0;
        result.allAreActive = true;

        for (ReleaseChangeProcess process : processesFor(releaseRoot)) {
            if (process.isEnabled() && result.everythingIsSynchronized
                    && Boolean.FALSE.equals(process.isSynchronized(releaseRoot.getResourceResolver()))) {
                result.everythingIsSynchronized = false;
            }
            result.allAreActive = result.allAreActive && process.isActive();
            if (process.isEnabled()) { result.numberEnabledProcesses++; }
            switch (process.getState()) {
                case error:
                    result.haveErrors = true;
                    result.replicationsAreRunning = true;
                    break;
                case awaiting:
                case processing:
                    result.replicationsAreRunning = true;
                    break;
                case idle:
                case success:
                case disabled:
                default:
                    // no action
                    break;
            }
        }
        return result;
    }

    @Override
    public void compareTree(@Nonnull ResourceHandle resource, boolean returnDetails, @Nullable String[] processIdParams, @Nonnull Map<String, Object> output) throws ReleaseChangeEventListener.ReplicationFailedException {
        for (ReleaseChangeProcess process : processesFor(resource)) {
            if (processIdParams != null && !Arrays.asList(processIdParams).contains(process.getId())) { continue; }
            CompareResult compareResult = process.compareTree(resource, returnDetails);
            if (compareResult != null) { output.put(process.getId(), compareResult); }
        }
    }

    @Activate
    @Modified
    protected void activate() {
        if (threadPool != null) { deactivate(); }
        LOG.info("activate");
        this.threadPool = threadPoolManager.get(THREADPOOL_NAME);
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("deactivate");
        ThreadPool oldThreadPool = this.threadPool;
        this.threadPool = null;
        if (oldThreadPool != null) { threadPoolManager.release(oldThreadPool); }
    }


}

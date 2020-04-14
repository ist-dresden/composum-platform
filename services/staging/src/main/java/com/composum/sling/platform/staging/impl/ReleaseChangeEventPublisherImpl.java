package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.replication.ReplicationConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Component(
        service = {ReleaseChangeEventPublisher.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Replication Service Publisher"
        }
)
public class ReleaseChangeEventPublisherImpl implements ReleaseChangeEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseChangeEventPublisherImpl.class);

    /**
     * Name of the threadpool, and thus prefix for the thread names.
     */
    public static final String THREADPOOL_NAME = "RCEventPub";

    /**
     * A time we wait before executing changes to try to make sure the transaction was already comitted.
     * If this is too low, this can lead to consistency problems!
     */
    protected static final long WAIT_FOR_COMMIT_TIME_MS = 3000; // FIXME(hps,23.03.20) set to 10s

    @Reference
    protected ThreadPoolManager threadPoolManager;

    protected final List<ReleaseChangeEventListener> releaseChangeEventListeners = Collections.synchronizedList(new ArrayList<>());

    protected volatile ThreadPool threadPool;

    /**
     * Object to lock over when changing {@link #runningProcesses} or {@link #queuedProcesses}.
     */
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
        if (event == null || event.isEmpty()) {
            return;
        }
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
                if (exception != null) {
                    e.addSuppressed(e);
                } else {
                    exception = e;
                }
            } catch (RuntimeException e) {
                LOG.error("Error publishing to {} the event {}", releaseChangeEventListener, event, e);
                ReleaseChangeEventListener.ReplicationFailedException newException = new ReleaseChangeEventListener.ReplicationFailedException("Error publishing to " + releaseChangeEventListener, e, event);
                if (exception != null) {
                    e.addSuppressed(newException);
                } else {
                    exception = newException;
                }
            }
        }
        if (exception != null) {
            throw exception;
        }
        // we trigger the processes only afterwards - throwing an exception here rolls back the changes

        Collection<ReleaseChangeProcess> processes = processesFor(event.release(), null);
        for (ReleaseChangeProcess process : processes) {
            if (!process.isEnabled()) {
                continue;
            }
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

    /**
     * Organizes that a process is rescheduled after being run if that's needed.
     */
    protected class RescheduleWrapper implements Runnable {

        protected final ReleaseChangeProcess process;

        public RescheduleWrapper(ReleaseChangeProcess process) {
            this.process = process;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(WAIT_FOR_COMMIT_TIME_MS);
                process.runReplication();
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
    public Collection<ReleaseChangeProcess> processesFor(@Nullable StagingReleaseManager.Release release, @Nullable String stage) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        if (release != null) {
            for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
                for (ReleaseChangeProcess releaseChangeProcess : releaseChangeEventListener.processesFor(release)) {
                    if (StringUtils.isBlank(stage) || stage.equals(releaseChangeProcess.getStage())) {
                        result.add(releaseChangeProcess);
                    }
                }
            }
        }
        result = filterImplicits(result);
        return result;
    }

    @Nonnull
    @Override
    public Collection<ReleaseChangeProcess> processesFor(@Nullable Resource releaseRoot, @Nullable String stage) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
            for (ReleaseChangeProcess releaseChangeProcess : releaseChangeEventListener.processesFor(releaseRoot)) {
                if (StringUtils.isBlank(stage) || stage.equals(releaseChangeProcess.getStage())) {
                    result.add(releaseChangeProcess);
                }
            }
        }
        result = filterImplicits(result);
        return result;
    }

    /**
     * If there is at least an explicit (i.e., not {@link ReleaseChangeProcess#isImplicit()} configuration, any implicit configurations are removed;
     * if there are no explicit configurations the implicit configurations are returned.
     */
    @Nonnull
    protected List<ReleaseChangeProcess> filterImplicits(@Nonnull List<ReleaseChangeProcess> result) {
        boolean haveExplicit = result.stream().anyMatch((p) -> !p.isImplicit());
        if (haveExplicit) { // remove implicit configurations
            return result.stream().filter((p) -> !p.isImplicit()).collect(Collectors.toList());
        } else { // only implicit configurations present, if at all.
            return result;
        }
    }

    @Nonnull
    @Override
    public Map<String, ReplicationStateInfo> replicationState(@Nullable Resource releaseRoot, @Nullable String stage) {
        Map<String, ReplicationStateInfo> result = new LinkedHashMap<>();
        if (releaseRoot != null) {
            for (ReleaseChangeProcess process : processesFor(releaseRoot, stage)) {
                ReplicationStateInfo info = new ReplicationStateInfo();
                info.id = process.getId();
                try {
                    info.state = process.getState();
                    info.name = process.getName();
                    info.description = process.getDescription();
                    info.type = process.getType();
                    info.startedAt = process.getRunStartedAt();
                    info.finishedAt = process.getRunFinished();
                    info.messages = process.getMessages();
                    info.enabled = process.isEnabled();
                    info.active = process.isActive();
                    info.completionPercentage = process.getCompletionPercentage();
                    process.updateSynchronized();
                    info.isSynchronized = process.isSynchronized(releaseRoot.getResourceResolver());
                    info.lastReplicationTimestamp = process.getLastReplicationTimestamp();
                    info.history.putAll(process.getHistory());
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                    // there was an IllegalArgumentException in a process (bad configuration)
                    // but a state retrieval should never throw exceptions - this breaks th UI
                    info.state = ReleaseChangeProcess.ReleaseChangeProcessorState.error;
                    info.messages = new MessageContainer();
                    info.messages.add(new Message(Message.Level.error, ex.getMessage()));
                }
                result.put(process.getId(), info);
            }
        }
        return result;
    }

    @Nullable
    @Override
    public AggregatedReplicationStateInfo aggregatedReplicationState(@Nullable Resource releaseRoot, @Nullable String stage) {
        if (releaseRoot == null) {
            return null;
        }
        AggregatedReplicationStateInfo result = new AggregatedReplicationStateInfo();
        result.replicationsAreRunning = false;
        result.haveErrors = false;
        result.everythingIsSynchronized = true;
        result.numberEnabledProcesses = 0;
        result.allAreActive = true;

        for (ReleaseChangeProcess process : processesFor(releaseRoot, stage)) {
            try {
                if (process.isEnabled() && result.everythingIsSynchronized
                        && !Boolean.TRUE.equals(process.isSynchronized(releaseRoot.getResourceResolver()))) {
                    result.everythingIsSynchronized = false;
                }
                result.allAreActive = result.allAreActive && process.isActive();
                if (process.isEnabled()) {
                    result.numberEnabledProcesses++;
                }
                switch (process.getState()) {
                    case error:
                        result.haveErrors = true;
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
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
                // there was an IllegalArgumentException in a process (bad configuration)
                // but a state retrieval should never throw exceptions - this breaks th UI
                result.haveErrors = true;
                result.allAreActive = false;
                result.everythingIsSynchronized = false;
            }
        }
        return result;
    }

    @Override
    public void abortReplication(@Nullable Resource releaseRoot, @Nullable String stage) {
        for (ReleaseChangeProcess releaseChangeProcess : processesFor(releaseRoot, stage)) {
            releaseChangeProcess.abort();
        }
    }

    @Override
    public void compareTree(@Nonnull ResourceHandle resource, int details, @Nullable String[] processIdParams,
                            @Nonnull Map<String, Object> output) throws ReleaseChangeEventListener.ReplicationFailedException {
        for (ReleaseChangeProcess process : processesFor(resource, null)) {
            if (processIdParams != null && !Arrays.asList(processIdParams).contains(process.getId())) {
                continue;
            }
            CompareResult compareResult = process.compareTree(resource, details);
            if (compareResult != null) {
                output.put(process.getId(), compareResult);
            }
        }
    }

    @Nullable
    @Override
    public String getStagePath(@Nullable Resource releaseRoot, @Nullable String stage) {
        Set<String> results = new HashSet<>();
        for (ReleaseChangeProcess process : processesFor(releaseRoot, stage)) {
            ReplicationConfig config = process.getReplicationConfig();
            if (config != null) {
                if (StringUtils.isBlank(config.getSourcePath()) || process.getReleaseRootPath().equals(config.getSourcePath())) {
                    results.add(StringUtils.defaultIfBlank(config.getTargetPath(), process.getReleaseRootPath()));
                }
            }
        }
        return results.size() == 1 ? results.iterator().next() : null; // only return if unique
    }

    @Activate
    @Modified
    protected void activate() {
        if (threadPool != null) {
            deactivate();
        }
        LOG.info("activate");
        this.threadPool = threadPoolManager.get(THREADPOOL_NAME);
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("deactivate");
        ThreadPool oldThreadPool = this.threadPool;
        this.threadPool = null;
        if (oldThreadPool != null) {
            threadPoolManager.release(oldThreadPool);
        }
    }


}

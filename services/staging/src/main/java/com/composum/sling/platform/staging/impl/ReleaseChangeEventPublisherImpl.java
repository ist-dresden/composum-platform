package com.composum.sling.platform.staging.impl;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
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

    @Reference
    protected ThreadPoolManager threadPoolManager;

    protected final List<ReleaseChangeEventListener> releaseChangeEventListeners = Collections.synchronizedList(new ArrayList<>());

    protected volatile ThreadPool threadPool;

    /** Keeps the results to keep track which processes are currently running. Synchronize over this! */
    protected final Map<ReleaseChangeProcess, Future<?>> runningProcesses = new WeakHashMap<>();


    @Reference(
            service = ReleaseChangeEventListener.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Adding listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        Iterator<ReleaseChangeEventListener> it = releaseChangeEventListeners.iterator();
        while (it.hasNext()) { if (it.next() == listener) { it.remove(); } }
        releaseChangeEventListeners.add(listener);
    }

    protected void removeReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Removing listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        Iterator<ReleaseChangeEventListener> it = releaseChangeEventListeners.iterator();
        while (it.hasNext()) { if (it.next() == listener) { it.remove(); } }
    }

    @Override
    public void publishActivation(ReleaseChangeEventListener.ReleaseChangeEvent event) throws ReleaseChangeEventListener.ReplicationFailedException {
        if (event == null || event.isEmpty()) { return; }
        event.finish();
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
            try {
                process.triggerProcessing(event);
                maybeDeployProcess(process);
            } catch (RuntimeException e) {
                LOG.error("Error when triggering process {} for {}", process, event, e);
            }
        }
    }

    // FIXME(hps,07.01.20) was ist mit retriggern / abbrechen?
    protected void maybeDeployProcess(@Nonnull ReleaseChangeProcess process) {
        synchronized (runningProcesses) {
            Future<?> future = runningProcesses.get(process);
            if (future != null && future.isDone()) {
                future = null;
                runningProcesses.remove(process);
            }
            if (future == null && process.getState() == ReleaseChangeProcess.ReleaseChangeProcessorState.awaiting) {
                future = threadPool.submit(process);
                runningProcesses.put(process, future);
            }
        }
    }

    @Override
    public Collection<ReleaseChangeProcess> processesFor(StagingReleaseManager.Release release) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
            result.addAll(releaseChangeEventListener.processesFor(release));
        }
        return result;
    }

    @Override
    public Collection<ReleaseChangeProcess> processesFor(Resource releaseRoot) {
        List<ReleaseChangeProcess> result = new ArrayList<>();
        for (ReleaseChangeEventListener releaseChangeEventListener : new ArrayList<>(this.releaseChangeEventListeners)) {
            result.addAll(releaseChangeEventListener.processesFor(releaseRoot));
        }
        return result;
    }

    @Activate
    @Modified
    protected void activate() {
        if (threadPool != null) { deactivate(); }
        LOG.info("activate");
        this.threadPool = threadPoolManager.get(ReleaseChangeEventPublisherImpl.class.getName());
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("deactivate");
        ThreadPool oldThreadPool = this.threadPool;
        this.threadPool = null;
        if (oldThreadPool != null) { threadPoolManager.release(oldThreadPool); }
    }


}

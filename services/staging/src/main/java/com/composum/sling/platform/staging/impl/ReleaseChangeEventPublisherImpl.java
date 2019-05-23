package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

@Component(
        service = {ReleaseChangeEventPublisher.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Platform Replication Service Publisher"
        }
)
public class ReleaseChangeEventPublisherImpl implements ReleaseChangeEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseChangeEventPublisherImpl.class);

    protected final List<ReleaseChangeEventListener> releaseChangeEventListeners = Collections.synchronizedList(new ArrayList<>());

    @Reference(
            service = ReleaseChangeEventListener.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Adding listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        Iterator<ReleaseChangeEventListener> it = releaseChangeEventListeners.iterator();
        while (it.hasNext()) if (it.next() == listener) it.remove();
        releaseChangeEventListeners.add(listener);
    }

    protected void removeReleaseChangeEventListener(@Nonnull ReleaseChangeEventListener listener) {
        LOG.info("Removing listener {}@{}", listener.getClass().getName(), System.identityHashCode(listener));
        Iterator<ReleaseChangeEventListener> it = releaseChangeEventListeners.iterator();
        while (it.hasNext()) if (it.next() == listener) it.remove();
    }

    @Override
    public void publishActivation(ReleaseChangeEventListener.ReleaseChangeEvent event) throws ReleaseChangeEventListener.ReplicationFailedException {
        if (event == null || event.isEmpty())
            return;
        event.finalize();
        ReleaseChangeEventListener.ReplicationFailedException exception = null;
        for (ReleaseChangeEventListener releaseChangeEventListener : releaseChangeEventListeners) {
            try {
                releaseChangeEventListener.receive(event);
                LOG.debug("published to {} : {}", releaseChangeEventListener, event);
            } catch (ReleaseChangeEventListener.ReplicationFailedException e) {
                LOG.error("Error publishing to {} the event {}", releaseChangeEventListener, event, e);
                if (exception != null) e.addSuppressed(e);
                else exception = e;
            } catch (RuntimeException e) {
                LOG.error("Error publishing to {} the event {}", releaseChangeEventListener, event, e);
                ReleaseChangeEventListener.ReplicationFailedException newException = new ReleaseChangeEventListener.ReplicationFailedException("Error publishing to " + releaseChangeEventListener, e, event);
                if (exception != null) e.addSuppressed(newException);
                else exception = newException;
            }
        }
        if (exception != null)
            throw exception;
    }

}

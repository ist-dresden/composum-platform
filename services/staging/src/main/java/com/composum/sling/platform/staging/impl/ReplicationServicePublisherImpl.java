package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.ReplicationService;
import com.composum.sling.platform.staging.ReplicationServicePublisher;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.commons.lang3.builder.ToStringBuilder;
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
import java.util.List;

@Component(
        service = {ReplicationServicePublisher.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Platform Replication Service Publisher"
        }
)
public class ReplicationServicePublisherImpl implements ReplicationServicePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationServicePublisherImpl.class);

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, service = ReplicationService.class, policy = ReferencePolicy.DYNAMIC)
    protected List<ReplicationService> replicationServices;

    @Nonnull
    protected List<String> wrap(@Nullable List<String> resources) {
        return resources != null ? Collections.unmodifiableList(new ArrayList<>(resources)) : Collections.emptyList();
    }

    @Override
    public void publishActivation(ReplicationService.ReleaseChangeEvent event) throws ReplicationService.ReplicationFailedException {
        if (event == null)
            return;
        ReplicationService.ReplicationFailedException exception = null;
        for (ReplicationService replicationService : replicationServices) {
            try {
                replicationService.receive(event);
                LOG.debug("published to {} : {}", replicationService, event);
            } catch (ReplicationService.ReplicationFailedException e) {
                LOG.error("Error publishing to {} the event {}", replicationService, event, e);
                if (exception != null) e.addSuppressed(e);
                else exception = e;
            } catch (RuntimeException e) {
                LOG.error("Error publishing to {} the event {}", replicationService, event, e);
                ReplicationService.ReplicationFailedException newException = new ReplicationService.ReplicationFailedException("Error publishing to " + replicationService, e, event);
                if (exception != null) e.addSuppressed(newException);
                else exception = newException;
            }
        }
        if (exception != null)
            throw exception;
    }

}

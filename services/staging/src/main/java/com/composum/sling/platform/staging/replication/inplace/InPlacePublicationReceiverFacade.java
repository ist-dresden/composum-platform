package com.composum.sling.platform.staging.replication.inplace;

import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend.RemotePublicationReceiverException;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;

public class InPlacePublicationReceiverFacade implements PublicationReceiverFacade {
    private static final Logger LOG = LoggerFactory.getLogger(InPlacePublicationReceiverFacade.class);
    private final InPlaceReplicationConfig config;
    private final BeanContext context;
    private final Supplier<InPlacePublisherService.Configuration> generalConfig;
    private final PublicationReceiverBackend backend;

    public InPlacePublicationReceiverFacade(InPlaceReplicationConfig replicationConfig, BeanContext context, Supplier<InPlacePublisherService.Configuration> generalConfig, PublicationReceiverBackend backend) {
        this.config = replicationConfig;
        this.context = context;
        this.generalConfig = generalConfig;
        this.backend = backend;
    }

    @Nonnull
    @Override
    public StatusWithReleaseData startUpdate(@Nonnull ReplicationPaths replicationPaths) throws PublicationReceiverFacadeException, RepositoryException {
        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.startUpdate(replicationPaths);
        } catch (PersistenceException | LoginException | RemotePublicationReceiverException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public StatusWithReleaseData releaseInfo(@Nonnull ReplicationPaths replicationPaths) throws PublicationReceiverFacadeException, RepositoryException {
        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.releaseInfo(replicationPaths);
        } catch (LoginException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public ContentStateStatus contentState(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver, @Nonnull ReplicationPaths replicationPaths) throws PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.contentState");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.contentState");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.contentState
        ContentStateStatus result = null;
        return result;
    }

    @Nonnull
    @Override
    public Status compareContent(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver, @Nonnull ReplicationPaths replicationPaths) throws URISyntaxException, PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.compareContent");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.compareContent");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.compareContent
        Status result = null;
        return result;
    }

    @Nonnull
    @Override
    public Status pathupload(@Nonnull UpdateInfo updateInfo, @Nonnull Resource resource) throws PublicationReceiverFacadeException, URISyntaxException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.pathupload");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.pathupload");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.pathupload
        Status result = null;
        return result;
    }

    @Nonnull
    @Override
    public Status commitUpdate(@Nonnull UpdateInfo updateInfo, @Nonnull String newReleaseChangeNumber, @Nonnull Set<String> deletedPaths, @Nonnull Stream<ChildrenOrderInfo> relevantOrderings, @Nonnull ExceptionThrowingRunnable<? extends Exception> checkForParallelModifications) throws PublicationReceiverFacadeException, RepositoryException {
        Status status = new Status(null, null, LOG);
        try {
            backend.commit(updateInfo.updateId, deletedPaths, () -> relevantOrderings.iterator(), newReleaseChangeNumber);
        } catch (PersistenceException | LoginException | RemotePublicationReceiverException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public Status abortUpdate(@Nonnull UpdateInfo updateInfo) throws PublicationReceiverFacadeException, RepositoryException {
        Status status = new Status(null, null, LOG);
        try {
            backend.abort(updateInfo.updateId);
        } catch (LoginException | RemotePublicationReceiverException | PersistenceException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Override
    public Status compareParents(@Nonnull ReplicationPaths replicationPaths, @Nonnull ResourceResolver resolver, @Nonnull Stream<ChildrenOrderInfo> relevantOrderings, @Nonnull Stream<NodeAttributeComparisonInfo> attributeInfos) throws PublicationReceiverFacadeException, RepositoryException {
        Status status = new Status(null, null, LOG);
        try {
            List<String> differentChildorderings = backend.compareChildorderings(replicationPaths, () -> relevantOrderings.iterator());
            status.data(PARAM_CHILDORDERINGS).put(PARAM_PATH, differentChildorderings);

            List<String> differentParentAttributes = backend.compareAttributes(replicationPaths, () -> attributeInfos.iterator());
            status.data(PARAM_ATTRIBUTEINFOS).put(PARAM_PATH, differentParentAttributes);
        } catch (LoginException | RemotePublicationReceiverException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }
}

package com.composum.sling.platform.staging.replication.inplace;

import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class InPlacePublicationReceiverFacade implements PublicationReceiverFacade {
    private static final Logger LOG = LoggerFactory.getLogger(InPlacePublicationReceiverFacade.class);

    public InPlacePublicationReceiverFacade(InPlaceReplicationConfig replicationConfig, BeanContext context, Supplier<InPlacePublisherService.Configuration> generalConfig) {
    }

    @Nonnull
    @Override
    public StatusWithReleaseData startUpdate(@Nonnull ReplicationPaths replicationPaths) throws PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.startUpdate");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.startUpdate");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.startUpdate
        StatusWithReleaseData result = null;
        return result;
    }

    @Nonnull
    @Override
    public StatusWithReleaseData releaseInfo(@Nonnull ReplicationPaths replicationPaths) throws PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.releaseInfo");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.releaseInfo");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.releaseInfo
        StatusWithReleaseData result = null;
        return result;
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
        LOG.error("InPlacePublicationReceiverFacade.commitUpdate");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.commitUpdate");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.commitUpdate
        Status result = null;
        return result;
    }

    @Nonnull
    @Override
    public Status abortUpdate(@Nonnull UpdateInfo updateInfo) throws PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.abortUpdate");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.abortUpdate");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.abortUpdate
        Status result = null;
        return result;
    }

    @Override
    public Status compareParents(@Nonnull ReplicationPaths replicationPaths, @Nonnull ResourceResolver resolver, @Nonnull Stream<ChildrenOrderInfo> relevantOrderings, @Nonnull Stream<NodeAttributeComparisonInfo> attributeInfos) throws PublicationReceiverFacadeException, RepositoryException {
        LOG.error("InPlacePublicationReceiverFacade.compareParents");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: InPlacePublicationReceiverFacade.compareParents");
        // FIXME hps 19.03.20 implement InPlacePublicationReceiverFacade.compareParents
        Status result = null;
        return result;
    }
}

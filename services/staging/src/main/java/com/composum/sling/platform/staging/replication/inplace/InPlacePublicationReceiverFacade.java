package com.composum.sling.platform.staging.replication.inplace;

import com.composum.platform.commons.util.ExceptionThrowingConsumer;
import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.platform.commons.util.OutputStreamInputStreamAdapter;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.nodes.NodesConfiguration;
import com.composum.sling.nodes.servlet.SourceModel;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationException;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend.RemotePublicationReceiverException;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.sling.api.resource.*;
import org.apache.sling.commons.threads.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;

public class InPlacePublicationReceiverFacade implements PublicationReceiverFacade {
    private static final Logger LOG = LoggerFactory.getLogger(InPlacePublicationReceiverFacade.class);
    protected final InPlaceReplicationConfig config;
    protected final BeanContext context;
    protected final Supplier<InPlacePublisherService.Configuration> generalConfig;
    protected final PublicationReceiverBackend backend;
    protected final ResourceResolverFactory resolverFactory;
    protected final NodesConfiguration nodesConfiguration;
    protected final ThreadPool threadPool;

    public InPlacePublicationReceiverFacade(InPlaceReplicationConfig replicationConfig, BeanContext context,
                                            Supplier<InPlacePublisherService.Configuration> generalConfig,
                                            PublicationReceiverBackend backend, ResourceResolverFactory resolverFactory,
                                            NodesConfiguration nodesConfiguration, ThreadPool threadPool) {
        this.config = replicationConfig;
        this.context = context;
        this.generalConfig = generalConfig;
        this.backend = backend;
        this.resolverFactory = resolverFactory;
        this.nodesConfiguration = nodesConfiguration;
        this.threadPool = threadPool;
    }

    /**
     * Creates the service resolver used to read / update the content.
     */
    protected ResourceResolver makeResolver() throws ReplicationException {
        try {
            return resolverFactory.getServiceResourceResolver(null);
        } catch (LoginException e) {
            throw new ReplicationException(Message.error("Could not get service user for facade"), e);
        }
    }

    @Nonnull
    @Override
    public StatusWithReleaseData startUpdate(@Nonnull ReplicationPaths replicationPaths) {
        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.startUpdate(replicationPaths);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (PersistenceException | RepositoryException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public StatusWithReleaseData releaseInfo(@Nonnull ReplicationPaths replicationPaths) {
        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.releaseInfo(replicationPaths);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (RuntimeException | RepositoryException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public ContentStateStatus contentState(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, @Nonnull ResourceResolver stagedResolver,
                                           @Nonnull ReplicationPaths replicationPaths) {
        ContentStateStatus status = new ContentStateStatus(LOG);
        VersionableTree backendResult = null;
        try (ResourceResolver resolver = makeResolver()) {
            backendResult = backend.contentStatus(replicationPaths, paths, resolver);
            status.versionables = new VersionableTree();
            status.versionables.process(backendResult.versionableInfos(replicationPaths.inverseTranslateMapping(backend.getChangeRoot())),
                    replicationPaths.getOrigin(), null, stagedResolver);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public Status compareContent(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver, @Nonnull ReplicationPaths replicationPaths) {
        Status status = new Status(null, null, LOG);

        try {
            VersionableTree versionableTree = new VersionableTree();
            Collection<Resource> resources = paths.stream()
                    .map(resolver::getResource)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            versionableTree.setSearchtreeRoots(resources);

            List<String> diffpaths = backend.compareContent(replicationPaths, updateInfo.updateId, versionableTree.versionableInfos(null));
            status.data(Status.DATA).put(PARAM_PATH, diffpaths);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    /**
     * {@inheritDoc}
     * <p>
     * For the implementation we create a package and unpack that, since this is easily compatible to the remote
     * replication process and an easy way to copy with existing means.
     */
    @Nonnull
    @Override
    public Status pathupload(@Nonnull UpdateInfo updateInfo, @Nonnull Resource resource) {
        Status status = new Status(null, null, LOG);
        Resource writeResource = resource;
        if (com.composum.sling.core.util.ResourceUtil.isFile(resource) && ResourceUtil.CONTENT_NODE.equals(resource.getName())) {
            // you need the parent node to form a correct package for this, since the file format is special.
            writeResource = resource.getParent();
        }
        SourceModel model = new SourceModel(nodesConfiguration, context, writeResource);
        ExceptionThrowingConsumer<OutputStream, IOException> writer = (outstream) -> {
            try {
                model.writePackage(outstream, "inplacepublisher", resource.getPath(), "1");
            } catch (SourceModel.IOErrorOnCloseException e) {
                LOG.debug("Error on zip close", e);
                // ignore - the reader doesn't read the central directory of the zip that is written on close.
            } catch (RepositoryException e) {
                throw new IOException(e);
            } catch (IOException e) {
                throw e;
            }
        };

        try (InputStream inputStream = OutputStreamInputStreamAdapter.of(writer, threadPool)) {
            backend.pathUpload(updateInfo.updateId, resource.getPath(), inputStream);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (RemotePublicationReceiverException | IOException | ConfigurationException | RepositoryException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public Status commitUpdate(@Nonnull UpdateInfo updateInfo, @Nonnull String newReleaseChangeNumber, @Nonnull Set<String> deletedPaths, @Nonnull Stream<ChildrenOrderInfo> relevantOrderings, @Nonnull ExceptionThrowingRunnable<? extends Exception> checkForParallelModifications) {
        Status status = new Status(null, null, LOG);
        try {
            backend.commit(updateInfo.updateId, deletedPaths, () -> relevantOrderings.iterator(), newReleaseChangeNumber);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (PersistenceException | RemotePublicationReceiverException | RuntimeException | RepositoryException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Nonnull
    @Override
    public Status abortUpdate(@Nonnull UpdateInfo updateInfo) {
        Status status = new Status(null, null, LOG);
        try {
            backend.abort(updateInfo.updateId);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (PersistenceException | RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }

    @Override
    public Status compareParents(@Nonnull ReplicationPaths replicationPaths, @Nonnull ResourceResolver resolver, @Nonnull Stream<ChildrenOrderInfo> relevantOrderings, @Nonnull Stream<NodeAttributeComparisonInfo> attributeInfos) {
        Status status = new Status(null, null, LOG);
        try {
            List<String> differentChildorderings = backend.compareChildorderings(replicationPaths, () -> relevantOrderings.iterator());
            status.data(PARAM_CHILDORDERINGS).put(PARAM_PATH, differentChildorderings);

            List<String> differentParentAttributes = backend.compareAttributes(replicationPaths, () -> attributeInfos.iterator());
            status.data(PARAM_ATTRIBUTEINFOS).put(PARAM_PATH, differentParentAttributes);
        } catch (ReplicationException e) {
            e.writeIntoStatus(status);
        } catch (RuntimeException e) {
            status.error("Internal error", e);
        }
        return status;
    }
}

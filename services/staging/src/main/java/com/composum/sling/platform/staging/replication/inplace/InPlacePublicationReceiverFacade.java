package com.composum.sling.platform.staging.replication.inplace;

import com.composum.platform.commons.util.ExceptionThrowingConsumer;
import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.platform.commons.util.OutputStreamInputStreamAdapter;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.nodes.NodesConfiguration;
import com.composum.sling.nodes.servlet.SourceModel;
import com.composum.sling.platform.staging.replication.PublicationReceiverFacade;
import com.composum.sling.platform.staging.replication.ReplicationException;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.impl.PublicationReceiverBackend;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.threads.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
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

    @NotNull
    @Override
    public StatusWithReleaseData startUpdate(@NotNull ReplicationPaths replicationPaths) throws ReplicationException {
        if (!replicationPaths.isMove()) {
            throw new ReplicationException(Message.error("An in-place replication must have a different target directory than source directory."), null);
        }
        if (StringUtils.startsWith(replicationPaths.getDestination(), "/content")) {
            throw new ReplicationException(Message.error("An in-place replication must have a target directory outside of /content - e.g. at /public or /preview."), null);
        }

        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.startUpdate(replicationPaths);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation startUpdate"), e);
        }
        return status;
    }

    @NotNull
    @Override
    public StatusWithReleaseData releaseInfo(@NotNull ReplicationPaths replicationPaths) throws ReplicationException {
        StatusWithReleaseData status = new StatusWithReleaseData(null, null, LOG);
        try {
            status.updateInfo = backend.releaseInfo(replicationPaths);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation releaseInfo"), e);
        }
        return status;
    }

    @NotNull
    @Override
    public ContentStateStatus contentState(@NotNull UpdateInfo updateInfo, @NotNull Collection<String> paths, @NotNull ResourceResolver stagedResolver,
                                           @NotNull ReplicationPaths replicationPaths) throws ReplicationException {
        ContentStateStatus status = new ContentStateStatus(LOG);
        VersionableTree backendResult = null;
        try (ResourceResolver resolver = makeResolver()) {
            backendResult = backend.contentStatus(replicationPaths, paths, resolver);
            status.versionables = new VersionableTree();
            status.versionables.process(backendResult.versionableInfos(replicationPaths.inverseTranslateMapping(backend.getChangeRoot())),
                    replicationPaths.getOrigin(), null, stagedResolver);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation contentState"), e);
        }
        return status;
    }

    @NotNull
    @Override
    public Status compareContent(@NotNull UpdateInfo updateInfo, @NotNull Collection<String> paths,
                                 @NotNull ResourceResolver resolver, @NotNull ReplicationPaths replicationPaths)
            throws ReplicationException {
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
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation compareContent"), e);
        }
        return status;
    }

    /**
     * {@inheritDoc}
     * <p>
     * For the implementation we create a package and unpack that, since this is easily compatible to the remote
     * replication process and an easy way to copy with existing means.
     */
    @NotNull
    @Override
    public Status pathupload(@NotNull UpdateInfo updateInfo, @NotNull Resource resource) throws ReplicationException {
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
                LOG.debug("Internal error on zip close", e);
                // ignore - the reader doesn't read the central directory of the zip that is written on close.
            } catch (RepositoryException e) {
                throw new IOException(e);
            } catch (IOException e) {
                throw e;
            }
        };

        try (InputStream inputStream = OutputStreamInputStreamAdapter.of(writer, threadPool)) {
            backend.pathUpload(updateInfo.updateId, resource.getPath(), inputStream);
        } catch (IOException | RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error during upload").setPath(SlingResourceUtil.getPath(resource)), e);
        }
        return status;
    }

    @NotNull
    @Override
    public Status commitUpdate(@NotNull UpdateInfo updateInfo, @NotNull String newReleaseChangeNumber, @NotNull Set<String> deletedPaths, @NotNull Supplier<Stream<ChildrenOrderInfo>> relevantOrderings, @NotNull ExceptionThrowingRunnable<? extends Exception> checkForParallelModifications) throws ReplicationException {
        Status status = new Status(null, null, LOG);
        try {
            backend.commit(updateInfo.updateId, deletedPaths, () -> relevantOrderings.get().iterator(), newReleaseChangeNumber);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation commitUpdate"), e);
        }
        return status;
    }

    @NotNull
    @Override
    public Status abortUpdate(@NotNull UpdateInfo updateInfo) throws ReplicationException {
        Status status = new Status(null, null, LOG);
        try {
            backend.abort(updateInfo.updateId);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation abortUpdate"), e);
        }
        return status;
    }

    @Override
    public Status compareParents(@NotNull ReplicationPaths replicationPaths, @NotNull ResourceResolver resolver, @NotNull Supplier<Stream<ChildrenOrderInfo>> relevantOrderings, @NotNull Supplier<Stream<NodeAttributeComparisonInfo>> attributeInfos) throws ReplicationException {
        Status status = new Status(null, null, LOG);
        try {
            List<String> differentChildorderings = backend.compareChildorderings(replicationPaths, () -> relevantOrderings.get().iterator());
            status.data(PARAM_CHILDORDERINGS).put(PARAM_PATH, differentChildorderings);

            List<String> differentParentAttributes = backend.compareAttributes(replicationPaths, () -> attributeInfos.get().iterator());
            status.data(PARAM_ATTRIBUTEINFOS).put(PARAM_PATH, differentParentAttributes);
        } catch (RuntimeException e) {
            throw new ReplicationException(Message.error("Internal error in operation commitUpdate"), e);
        }
        return status;
    }
}

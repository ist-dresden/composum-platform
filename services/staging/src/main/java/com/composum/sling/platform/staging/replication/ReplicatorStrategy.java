package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;
import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;
import static java.util.Objects.requireNonNull;

/**
 * Responsible for one replication.
 */
public class ReplicatorStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicatorStrategy.class);

    @Nonnull
    protected final Set<String> changedPaths;
    @Nonnull
    protected final StagingReleaseManager.Release release;
    @Nonnull
    protected final ResourceResolver resolver;
    @Nonnull
    protected final BeanContext context;
    @Nonnull
    protected final ReplicationConfig replicationConfig;
    @Nonnull
    protected final String originalSourceReleaseChangeNumber;
    @Nonnull
    protected final PublicationReceiverFacade publisher;
    @Nonnull
    protected final MessageContainer messages;

    protected volatile int progress;

    /**
     * If set, the replication process is aborted at the next step when this is checked.
     */
    protected volatile boolean abortAtNextPossibility = false;
    protected volatile UpdateInfo cleanupUpdateInfo;

    public ReplicatorStrategy(@Nonnull Set<String> changedPaths, @Nonnull StagingReleaseManager.Release release,
                              @Nonnull BeanContext context, @Nonnull ReplicationConfig replicationConfig,
                              @Nonnull MessageContainer messages, @Nonnull PublicationReceiverFacade publisher) {
        this.changedPaths = changedPaths;
        this.release = release;
        this.originalSourceReleaseChangeNumber = release.getChangeNumber();
        this.resolver = context.getResolver();
        this.context = context;
        this.replicationConfig = replicationConfig;
        this.messages = messages;
        this.publisher = publisher;
    }

    /**
     * Sets a mark that leads to aborting the process at the next step - if an outside interruption is necessary
     * for some reason.
     */
    public void setAbortAtNextPossibility() {
        messages.add(Message.info("Abort requested"));
        abortAtNextPossibility = true;
    }

    public void replicate() throws ReleaseChangeEventListener.ReplicationFailedException {
        cleanupUpdateInfo = null;
        try {
            String commonParent = SlingResourceUtil.commonParent(changedPaths);
            LOG.info("Changed paths below {}: {}", commonParent, changedPaths);
            requireNonNull(commonParent);
            progress = 0;

            UpdateInfo updateInfo = publisher.startUpdate(release.getReleaseRoot().getPath(), commonParent).updateInfo;
            cleanupUpdateInfo = updateInfo;
            LOG.info("Received UpdateInfo {}", updateInfo);

            if (originalSourceReleaseChangeNumber.equals(updateInfo.originalPublisherReleaseChangeId)) {
                messages.add(Message.info("Abort publishing since content on remote system is up to date."));
                return; // abort is called in finally
            }
            messages.add(Message.info("Update {} started", updateInfo.updateId));

            PublicationReceiverFacade.ContentStateStatus contentState = publisher.contentState(updateInfo,
                    changedPaths, resolver, release.getReleaseRoot().getPath());
            if (!contentState.isValid()) {
                messages.add(Message.error("Received invalid status on contentState for {}", updateInfo.updateId));
                throw new ReleaseChangeEventListener.ReplicationFailedException("Querying content state failed for " + replicationConfig,
                        null, null);
            }
            messages.add(Message.info("Content difference on remote side: {} , deleted {}",
                    contentState.getVersionables().getChangedPaths(), contentState.getVersionables().getDeletedPaths()));
            abortIfNecessary(updateInfo);

            Status compareContentState = publisher.compareContent(updateInfo, changedPaths, resolver, commonParent);
            if (!compareContentState.isValid()) {
                messages.add(Message.error("Received invalid status on compare content for {}",
                        updateInfo.updateId));
                throw new ReleaseChangeEventListener.ReplicationFailedException("Comparing content failed for " + replicationConfig, null,
                        null);
            }
            @SuppressWarnings("unchecked") List<String> remotelyDifferentPaths =
                    (List<String>) compareContentState.data(Status.DATA).get(PARAM_PATH);
            messages.add(Message.info("Different paths in target: {}", remotelyDifferentPaths));

            Set<String> pathsToTransmit = new LinkedHashSet<>(remotelyDifferentPaths);
            pathsToTransmit.addAll(contentState.getVersionables().getChangedPaths());
            Set<String> deletedPaths = new LinkedHashSet<>(contentState.getVersionables().getDeletedPaths());
            pathsToTransmit.addAll(deletedPaths); // to synchronize parents
            int count = 0;
            for (String path : pathsToTransmit) {
                abortIfNecessary(updateInfo);
                ++count;
                progress = 89 * (count) / pathsToTransmit.size();

                Resource resource = resolver.getResource(path);
                if (resource == null) { // we need to transmit the parent nodes of even deleted resources
                    deletedPaths.add(path);
                    resource = new NonExistingResource(resolver, path);
                }

                Status status = publisher.pathupload(updateInfo, resource);
                if (status == null || !status.isValid()) {
                    messages.add(Message.error("Received invalid status on pathupload {} : {}", path, status));
                    throw new ReleaseChangeEventListener.ReplicationFailedException("Upload failed for " + replicationConfig + " " +
                            "path " + path, null, null);
                } else {
                    messages.add(Message.debug("Uploaded {} for {}", path, updateInfo.updateId));
                }
            }

            abortIfNecessary(updateInfo);
            progress = 90;

            Stream<ChildrenOrderInfo> relevantOrderings = relevantOrderings(changedPaths);

            Status status = publisher.commitUpdate(updateInfo, originalSourceReleaseChangeNumber, deletedPaths,
                    relevantOrderings, () -> abortIfNecessary(updateInfo));
            if (!status.isValid()) {
                messages.add(Message.error("Received invalid status on commit {}", updateInfo.updateId));
                progress = 0;
                throw new ReleaseChangeEventListener.ReplicationFailedException("Commit failed for " + replicationConfig, null, null);
            }
            progress = 100;
            cleanupUpdateInfo = null;

            messages.add(Message.info("Replication done for {}", updateInfo.updateId));
        } catch (RuntimeException | PublicationReceiverFacade.PublicationReceiverFacadeException | URISyntaxException | RepositoryException e) {
            messages.add(Message.error("Replication failed for {} because of {}",
                    cleanupUpdateInfo != null ? cleanupUpdateInfo.updateId : "",
                    String.valueOf(e)), e);
            throw new ReleaseChangeEventListener.ReplicationFailedException("Replication failed for " + replicationConfig, e, null);
        } finally {
            if (cleanupUpdateInfo != null) { // remove temporary directory.
                try {
                    abort(cleanupUpdateInfo);
                } catch (Exception e) { // notify user since temporary directory can ge huge
                    messages.add(Message.error("Error cleaning up {}", cleanupUpdateInfo.updateId), e);
                }
            }
        }
    }

    /**
     * Returns childnode orderings of all parent nodes of {pathsToTransmit} and, if any of pathsToTransmit
     * has versionables as subnodes, of their parent nodes, too. (This needs to work for a full release sync, too.)
     * <p>
     * (There is one edge case we ignore deliberately: if a page was moved several times without any successful sync,
     * that might change some parent orderings there. If it's moved again and now it's synced, we might miss something.
     * That's a rare case which we could catch only if all node orderings are transmitted on each change, which
     * we hesitate to do for efficiency.)
     */
    @Nonnull
    protected Stream<ChildrenOrderInfo> relevantOrderings(@Nonnull Collection<String> pathsToTransmit) {
        Stream<Resource> relevantNodes = relevantParentNodesOfVersionables(pathsToTransmit);
        return relevantNodes
                .map(ChildrenOrderInfo::of)
                .filter(Objects::nonNull);
    }

    /**
     * The attribute infos for all parent nodes of versionables within pathsToTransmit within the release root.
     */
    @Nonnull
    protected Stream<NodeAttributeComparisonInfo> parentAttributeInfos(@Nonnull Collection<String> pathsToTransmit) {
        return relevantParentNodesOfVersionables(pathsToTransmit)
                .map(resource -> NodeAttributeComparisonInfo.of(resource, null))
                .filter(Objects::nonNull);
    }

    /**
     * Stream of all parent nodes of the versionables within pathsToTransmit that are within the release root.
     * This consists of the parent nodes of pathsToTransmit and the children of pathsToTransmit (including
     * themselves) up to (and excluding) the versionables.
     */
    @Nonnull
    protected Stream<Resource> relevantParentNodesOfVersionables(@Nonnull Collection<String> pathsToTransmit) {
        Stream<Resource> parentsStream = pathsToTransmit.stream()
                .flatMap(this::parentsUpToRelease)
                .distinct()
                .map(resolver::getResource)
                .filter(Objects::nonNull);
        Stream<Resource> childrenStream = pathsToTransmit.stream()
                .distinct()
                .map(resolver::getResource)
                .filter(Objects::nonNull)
                .flatMap(this::childrenExcludingVersionables);
        return Stream.concat(parentsStream, childrenStream);
    }

    @Nonnull
    protected Stream<String> parentsUpToRelease(String path) {
        List<String> result = new ArrayList<>();
        String parent = ResourceUtil.getParent(path);
        while (parent != null && isSameOrDescendant(release.getReleaseRoot().getPath(), parent)) {
            result.add(parent);
            parent = ResourceUtil.getParent(parent);
        }
        return result.stream();
    }

    @Nonnull
    protected Stream<Resource> childrenExcludingVersionables(Resource resource) {
        if (resource == null || ResourceUtil.isNodeType(resource, ResourceUtil.TYPE_VERSIONABLE)) {
            return Stream.empty();
        }
        return Stream.concat(Stream.of(resource),
                StreamSupport.stream(resource.getChildren().spliterator(), false)
                        .flatMap(this::childrenExcludingVersionables));
    }

    protected void abortIfNecessary(@Nonnull UpdateInfo updateInfo) throws PublicationReceiverFacade.PublicationReceiverFacadeException,
            ReleaseChangeEventListener.ReplicationFailedException {
        if (abortAtNextPossibility) {
            messages.add(Message.info("Aborting because that was requested: {}", updateInfo.updateId));
            abort(updateInfo);
            throw new ReleaseChangeEventListener.ReplicationFailedException("Aborted publishing because that was requested.", null,
                    null);
        }
        release.getMetaDataNode().getResourceResolver().refresh(); // might be a performance risk (?), but necessary
        if (!release.getChangeNumber().equals(originalSourceReleaseChangeNumber)) {
            messages.add(Message.info("Aborting {} because of local release content change during " +
                    "publishing.", updateInfo.updateId));
            abort(updateInfo);
            throw new ReleaseChangeEventListener.ReplicationFailedException("Abort publishing because of local release content change " +
                    "during publishing.", null, null);
        }
    }

    protected void abort(@Nonnull UpdateInfo updateInfo) throws PublicationReceiverFacade.PublicationReceiverFacadeException {
        Status status = null;
        try {
            status = publisher.abortUpdate(updateInfo);
            if (status == null || !status.isValid()) {
                messages.add(Message.error("Aborting replication failed for {} - " +
                        "please manually clean up resources used there.", updateInfo.updateId));
            } else if (cleanupUpdateInfo == updateInfo) {
                cleanupUpdateInfo = null;
            }
        } catch (RepositoryException e) {
            throw new PublicationReceiverFacade.PublicationReceiverFacadeException("Error calling abort", e, status, null);
        }

    }

    @Nullable
    public UpdateInfo remoteReleaseInfo() throws PublicationReceiverFacade.PublicationReceiverFacadeException {
        PublicationReceiverFacade.StatusWithReleaseData status = null;
        try {
            status = publisher.releaseInfo(release.getReleaseRoot().getPath());
        } catch (RepositoryException e) {
            throw new PublicationReceiverFacade.PublicationReceiverFacadeException("Error calling " +
                    "releaseInfo", e, status, null);
        }
        if (status == null || !status.isValid()) {
            LOG.error("Retrieve remote releaseinfo failed for {}", this.replicationConfig);
            messages.add(Message.error("Retrieve remote releaseinfo failed."));
            return null;
        }
        return status.updateInfo;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ReplicatorStrategy{");
        sb.append("id=").append(replicationConfig.getPath());
        if (cleanupUpdateInfo != null) {
            sb.append(", updateInfo=").append(cleanupUpdateInfo.updateId);
        }
        sb.append('}');
        return sb.toString();
    }

    @Nullable
    public ReleaseChangeEventPublisher.CompareResult compareTree(int details) throws PublicationReceiverFacade.PublicationReceiverFacadeException, ReleaseChangeEventListener.ReplicationFailedException {
        try {
            ReleaseChangeEventPublisher.CompareResult result = new ReleaseChangeEventPublisher.CompareResult();
            PublicationReceiverFacade.StatusWithReleaseData releaseInfoStatus = publisher.releaseInfo(release.getReleaseRoot().getPath());
            UpdateInfo updateInfo = releaseInfoStatus.updateInfo;
            if (!releaseInfoStatus.isValid() || updateInfo == null) {
                LOG.error("Retrieve remote releaseinfo failed for {}", this.replicationConfig);
                return null;
            }
            result.releaseChangeNumbersEqual = StringUtils.equals(release.getChangeNumber(),
                    updateInfo.originalPublisherReleaseChangeId);

            // get info on the remote versionables and check which are changed / not present here
            String commonParent = SlingResourceUtil.commonParent(changedPaths);
            PublicationReceiverFacade.ContentStateStatus contentState =
                    publisher.contentState(updateInfo, changedPaths, resolver, commonParent);
            if (!contentState.isValid() || contentState.getVersionables() == null) {
                throw new ReleaseChangeEventListener.ReplicationFailedException("Querying content state failed for " + replicationConfig + " " +
                        "path " + commonParent, null, null);
            }
            VersionableTree contentStateComparison = contentState.getVersionables();

            // check which of our versionables are changed / not present on the remote
            Status compareContentState = publisher.compareContent(updateInfo, changedPaths, resolver, commonParent);
            if (!compareContentState.isValid()) {
                throw new ReleaseChangeEventListener.ReplicationFailedException("Comparing content failed for " + replicationConfig, null,
                        null);
            }
            @SuppressWarnings("unchecked") List<String> remotelyDifferentPaths =
                    (List<String>) compareContentState.data(Status.DATA).get(PARAM_PATH);

            Set<String> differentPaths = new LinkedHashSet<>();
            differentPaths.addAll(remotelyDifferentPaths);
            differentPaths.addAll(contentStateComparison.getDeletedPaths());
            differentPaths.addAll(contentStateComparison.getChangedPaths());
            result.differentVersionablesCount = differentPaths.size();
            if (details > 0) {
                result.differentVersionables = differentPaths.toArray(new String[0]);
            }

            // compare the children orderings and parent attributes
            Stream<ChildrenOrderInfo> relevantOrderings = relevantOrderings(changedPaths);
            Stream<NodeAttributeComparisonInfo> attributeInfos = parentAttributeInfos(changedPaths);
            Status compareParentState = publisher.compareParents(release.getReleaseRoot().getPath(), resolver,
                    relevantOrderings, attributeInfos);
            if (!compareParentState.isValid()) {
                throw new ReleaseChangeEventListener.ReplicationFailedException("Comparing parents failed for " + replicationConfig, null,
                        null);
            }
            List<String> differentChildorderings = (List<String>) compareParentState.data(PARAM_CHILDORDERINGS).get(PARAM_PATH);
            List<String> changedAttributes = (List<String>) compareParentState.data(PARAM_ATTRIBUTEINFOS).get(PARAM_PATH);
            result.changedChildrenOrderCount = differentChildorderings.size();
            result.changedParentNodeCount = changedAttributes.size();
            if (details > 0) {
                result.changedChildrenOrders = differentChildorderings.toArray(new String[0]);
                result.changedParentNodes = changedAttributes.toArray(new String[0]);
            }

            // repeat releaseInfo since this might have taken a while and there might have been a change
            releaseInfoStatus = publisher.releaseInfo(release.getReleaseRoot().getPath());
            if (!releaseInfoStatus.isValid() || updateInfo == null) {
                LOG.error("Retrieve remote releaseinfo failed for {}", this.replicationConfig);
                return null;
            }
            result.releaseChangeNumbersEqual = result.releaseChangeNumbersEqual &&
                    StringUtils.equals(release.getChangeNumber(), updateInfo.originalPublisherReleaseChangeId);

            result.equal = result.calculateEqual();
            return result;
        } catch (URISyntaxException | RepositoryException e) {
            LOG.error("" + e, e);
            throw new ReleaseChangeEventListener.ReplicationFailedException("Internal error", e, null);
        }
    }

    public int getProgress() {
        return progress;
    }
}

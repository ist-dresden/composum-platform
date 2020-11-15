package com.composum.sling.platform.staging.replication;

import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import com.google.gson.GsonBuilder;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Business-Interface through which the replication algorithm can interface with the publisher server for replicating content.
 * This can be implemented locally in the server, but could also be routed remotely to a publisher host.
 */
public interface PublicationReceiverFacade {

    /**
     * Starts an update process on the remote side. To clean up resources, either
     * {@link #commitUpdate(UpdateInfo, String, Set, Stream, ExceptionThrowingRunnable)} or
     * {@link #abortUpdate(UpdateInfo)} must be called afterwards.
     *
     * @param replicationPaths information about the release root, source and target paths during replication.
     *                         The {@link ReplicationPaths#getContentPath()} can contain the root content path that should be considered,
     *                         if it is only a subpath of the {@link ReplicationPaths#getOrigin()}.
     * @return the basic information about the update which must be used for all related calls on this update.
     */
    @Nonnull
    StatusWithReleaseData startUpdate(@Nonnull ReplicationPaths replicationPaths) throws ReplicationException;

    /**
     * Starts an update process on the remote side. To clean up resources, either
     * {@link #commitUpdate(UpdateInfo, String, Set, Stream, ExceptionThrowingRunnable)} or
     * {@link #abortUpdate(UpdateInfo)} must be called afterwards.
     *
     * @param replicationPaths information about the release root, source and target paths during replication.
     * @return the basic information about the update which must be used for all related calls on this update.
     */
    @Nonnull
    StatusWithReleaseData releaseInfo(@Nonnull ReplicationPaths replicationPaths) throws ReplicationException;

    /**
     * Queries the versions of versionables below {paths} on the remote side and returns in the status which
     * resources of the remote side have a different version and which do not exist.
     *
     * @param paths            the paths to query
     * @param replicationPaths information about the replicated paths;
     *                         the {@link ReplicationPaths#getContentPath()} is a path that is a common parent to all paths
     *                         - just a safety feature that a broken / faked
     */
    @Nonnull
    ContentStateStatus contentState(
            @Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver,
            @Nonnull ReplicationPaths replicationPaths)
            throws ReplicationException;

    /**
     * Transmits the versions of versionables below {paths} to the remote side, which returns a list of paths
     * that have different versions or do not exists with {@link Status#data(String)}({@value Status#DATA}) attribute
     * {@link RemoteReceiverConstants#PARAM_PATH} as List&lt;String>.
     */
    @Nonnull
    Status compareContent(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths,
                          @Nonnull ResourceResolver resolver, @Nonnull ReplicationPaths replicationPaths)
            throws ReplicationException;

    /**
     * Uploads the resource tree to the remote machine.
     */
    @Nonnull
    Status pathupload(@Nonnull UpdateInfo updateInfo, @Nonnull Resource resource) throws ReplicationException;

    /**
     * Replaces the content with the updated content and deletes obsolete paths.
     *
     * @param checkForParallelModifications executed at the last possible time before the request is completed, to allow
     *                                      checking for parallel modifications of the source
     */
    @Nonnull
    Status commitUpdate(@Nonnull UpdateInfo updateInfo, @Nonnull String newReleaseChangeNumber,
                        @Nonnull Set<String> deletedPaths,
                        @Nonnull Supplier<Stream<ChildrenOrderInfo>> relevantOrderings,
                        @Nonnull ExceptionThrowingRunnable<? extends Exception> checkForParallelModifications)
            throws ReplicationException;

    /**
     * Aborts the update, deleting the temporary directory on the remote side.
     */
    @Nonnull
    Status abortUpdate(@Nonnull UpdateInfo updateInfo) throws ReplicationException;

    /**
     * Compares children order and attributes of the parents.
     * Returns in .data(PARAM_CHILDORDERINGS).get(PARAM_PATH) a list of paths that have different children ordering and in
     * .data(PARAM_ATTRIBUTEINFOS).get(PARAM_PATH) a list of paths that have different attributes.
     */
    Status compareParents(@Nonnull ReplicationPaths replicationPaths, @Nonnull ResourceResolver resolver,
                          @Nonnull Supplier<Stream<ChildrenOrderInfo>> relevantOrderings,
                          @Nonnull Supplier<Stream<NodeAttributeComparisonInfo>> attributeInfos)
            throws ReplicationException;

    /**
     * Extends Status to write data about all versionables below resource without needing to save everything in
     * memory - the data is fetched on the fly during JSON serialization.
     */
    class ContentStateStatus extends Status {

        /**
         * The attribute; need to register serializer - see {@link VersionableTree}.
         */
        public VersionableTree versionables;

        public VersionableTree getVersionables() {
            return versionables;
        }

        public ContentStateStatus(@Nonnull final GsonBuilder gsonBuilder, @Nonnull SlingHttpServletRequest request,
                                  @Nonnull SlingHttpServletResponse response, @Nonnull Logger logger) {
            super(gsonBuilder, request, response, logger);
        }

        public ContentStateStatus(@Nonnull Logger logger) {
            super(null, null, logger);
        }


        /**
         * @deprecated for instantiation by GSon only
         */
        @Deprecated
        public ContentStateStatus() {
            super(null, null);
        }

    }

    /**
     * Result of {@link #startUpdate(String, String)} and similar operations providing an {@link UpdateInfo}.
     */
    class StatusWithReleaseData extends Status {

        /**
         * The created update data.
         */
        public UpdateInfo updateInfo;

        /**
         * @deprecated for instantiation by GSon only
         */
        @Deprecated
        public StatusWithReleaseData() {
            super(null, null);
        }

        public StatusWithReleaseData(SlingHttpServletRequest request, SlingHttpServletResponse response, Logger log) {
            super(request, response, log);
        }
    }
}

package com.composum.sling.platform.staging.replication;

import com.composum.platform.commons.util.ExceptionThrowingRunnable;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import com.composum.sling.core.servlet.Status;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import org.apache.http.StatusLine;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Business-Interface through which the replication algorithm can interface with the publisher server for replicating content.
 * This can be implemented locally in the server, but could also be routed remotely to a publisher host.
 */
public interface PublicationReceiverFacade {

    /**
     * Starts an update process on the remote side. To clean up resources, either
     * {@link #commitUpdate(UpdateInfo, Set, Stream, ExceptionThrowingRunnable)} or
     * {@link #abortUpdate(UpdateInfo)} must be called afterwards.
     *
     * @param releaseRoot the root of the release containing {path} (may be equal to {path})
     * @param path        the root content path that should be considered. Might be the root of a release, or any
     *                    subdirectory.
     * @param srcPath     the path of the content affected by this configuration, default: releaseRoot
     * @param targetPath  the optional path at the target (for reference transformation), default: srcPath
     * @return the basic information about the update which must be used for all related calls on this update.
     */
    @Nonnull
    StatusWithReleaseData startUpdate(@NotNull String releaseRoot, @Nonnull String path,
                                      @Nullable String srcPath, @Nullable String targetPath)
            throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Starts an update process on the remote side. To clean up resources, either
     * {@link #commitUpdate(UpdateInfo, Set, Stream, ExceptionThrowingRunnable)} or
     * {@link #abortUpdate(UpdateInfo)} must be called afterwards.
     *
     * @param path            the root content path that should be considered. Might be the root of a release, or any
     *                        subdirectory.
     * @param releaseRootPath the root of the release containing {path} (may be equal to {path})
     * @return the basic information about the update which must be used for all related calls on this update.
     */
    @Nonnull
    StatusWithReleaseData releaseInfo(@NotNull String releaseRootPath) throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Queries the versions of versionables below {paths} on the remote side and returns in the status which
     * resources of the remote side have a different version and which do not exist.
     *
     * @param paths       the paths to query
     * @param contentPath a path that is a common parent to all paths - just a safety feature that a broken / faked
     *                    response cannot compare unwanted areas of the content.
     */
    @Nonnull
    ContentStateStatus contentState(
            @Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths, ResourceResolver resolver, String contentPath)
            throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Transmits the versions of versionables below {paths} to the remote side, which returns a list of paths
     * that have different versions or do not exists with {@link Status#data(String)}({@value Status#DATA}) attribute
     * {@link RemoteReceiverConstants#PARAM_PATH} as List&lt;String>.
     */
    @Nonnull
    Status compareContent(@Nonnull UpdateInfo updateInfo, @Nonnull Collection<String> paths,
                          ResourceResolver resolver, String contentPath)
            throws URISyntaxException, PublicationReceiverFacadeException, RepositoryException;

    /**
     * Uploads the resource tree to the remote machine.
     */
    @Nonnull
    Status pathupload(@Nonnull UpdateInfo updateInfo, @Nonnull Resource resource) throws PublicationReceiverFacadeException, URISyntaxException, RepositoryException;

    /**
     * Replaces the content with the updated content and deletes obsolete paths.
     *
     * @param checkForParallelModifications executed at the last possible time before the request is completed, to allow
     *                                      checking for parallel modifications of the source
     */
    @Nonnull
    Status commitUpdate(@Nonnull UpdateInfo updateInfo, @Nonnull String newReleaseChangeNumber,
                        @Nonnull Set<String> deletedPaths,
                        @Nonnull Stream<ChildrenOrderInfo> relevantOrderings,
                        @Nonnull ExceptionThrowingRunnable<? extends Exception> checkForParallelModifications)
            throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Aborts the update, deleting the temporary directory on the remote side.
     */
    @Nonnull
    Status abortUpdate(@Nonnull UpdateInfo updateInfo) throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Compares children order and attributes of the parents.
     * Returns in .data(PARAM_CHILDORDERINGS).get(PARAM_PATH) a list of paths that have different children ordering and in
     * .data(PARAM_ATTRIBUTEINFOS).get(PARAM_PATH) a list of paths that have different attributes.
     */
    Status compareParents(String releaseRoot, ResourceResolver resolver, Stream<ChildrenOrderInfo> relevantOrderings,
                          Stream<NodeAttributeComparisonInfo> attributeInfos) throws PublicationReceiverFacadeException, RepositoryException;

    /**
     * Exception that signifies a problem with the replication.
     */
    class PublicationReceiverFacadeException extends Exception {
        private static final Logger LOG = LoggerFactory.getLogger(PublicationReceiverFacadeException.class);

        protected final Status status;
        protected final Integer statusCode;
        protected final String reasonPhrase;

        public PublicationReceiverFacadeException(String message, Throwable throwable, Status status, StatusLine statusLine) {
            super(message, throwable);
            this.status = status;
            this.statusCode = statusLine != null ? statusLine.getStatusCode() : null;
            this.reasonPhrase = statusLine != null ? statusLine.getReasonPhrase() : null;
        }

        @Nullable
        public Status getStatus() {
            return status;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(super.toString()).append("{");
            if (statusCode != null) {
                sb.append(", statusCode=").append(statusCode);
            }
            if (reasonPhrase != null) {
                sb.append(", reasonPhrase='").append(reasonPhrase).append('\'');
            }
            if (status != null) {
                try (StringWriter statusString = new StringWriter()) {
                    status.toJson(new JsonWriter(statusString));
                    sb.append(", status=").append(statusString.toString());
                } catch (IOException e) {
                    LOG.error("" + e, e);
                    sb.append(", status=Cannot deserialize: ").append(e);
                }
            }
            sb.append('}');
            return sb.toString();
        }
    }

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

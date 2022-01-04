package com.composum.sling.platform.staging.replication.impl;

import com.composum.sling.platform.staging.replication.ReplicationException;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Interface for service that implements the functions behind the {@link RemotePublicationReceiverServlet}.
 */
public interface PublicationReceiverBackend {

    boolean isEnabled();

    /**
     * From the configuration a directory where we synchronize to - in production use / , but while
     * testing / debugging this might be elsewhere, e.g. /tmp/composum/platform/replicationtest .
     */
    String getChangeRoot();

    /**
     * Prepares the temporary directory for an update operation. Take care to remove it later!
     */
    UpdateInfo startUpdate(@NotNull ReplicationPaths replicationPaths)
            throws ReplicationException;

    /**
     * Uploads one package into the temporary directory, taking note of the root path for later moving to content.
     */
    void pathUpload(@Nullable String updateId, @NotNull String packageRootPath, @NotNull InputStream inputStream)
            throws ReplicationException;

    /**
     * Moves the content to the content directory and deletes the given paths, thus finalizing the update. The
     * temporary directory is then deleted.
     */
    void commit(@NotNull String updateId, @NotNull Set<String> deletedPaths,
                @NotNull Iterable<ChildrenOrderInfo> childOrderings, String newReleaseChangeId)
            throws ReplicationException;

    /**
     * Retrieves a list of {@link VersionableInfo} from the {jsonInputStream}, checks these against the content and
     * returns the paths where differences in the version number exist / paths that do not exist.
     */
    @NotNull
    List<String> compareContent(@Nullable ReplicationPaths replicationPaths, @Nullable String updateId,
                                @NotNull Stream<VersionableInfo> versionableInfos)
            throws ReplicationException;

    /**
     * Aborts the update operation and deletes the temporary directory.
     */
    void abort(@Nullable String updateId)
            throws ReplicationException;

    /**
     * Gets general info about a release without starting an update.
     */
    @Nullable
    UpdateInfo releaseInfo(@NotNull ReplicationPaths replicationPaths) throws ReplicationException;

    /**
     * Reads childorderings as {@link ChildrenOrderInfo} and compares these to whatever we have in our repository,
     * and returns the paths where it's different.
     */
    @NotNull
    List<String> compareChildorderings(@NotNull ReplicationPaths replicationPaths,
                                       @NotNull Iterable<ChildrenOrderInfo> childOrderings)
            throws ReplicationException;

    /**
     * Reads node attribute information {@link NodeAttributeComparisonInfo}  and compares these to whatever we have
     * in our repository, and returns the paths where it's different.
     */
    @NotNull
    List<String> compareAttributes(@NotNull ReplicationPaths replicationPaths,
                                   @NotNull Iterable<NodeAttributeComparisonInfo> attributeInfos) throws ReplicationException;

    /**
     * Generates a {@link VersionableTree} from which one can retrieve the {@link VersionableInfo}s below a set of paths.
     *
     * @param resolver the resolver used; it's necessary to route that in since it canot be closed within the method since
     *                 VersionableTree contains resources from this resolver
     */
    VersionableTree contentStatus(@NotNull ReplicationPaths replicationPaths, @NotNull Collection<String> paths, @NotNull ResourceResolver resolver);

}

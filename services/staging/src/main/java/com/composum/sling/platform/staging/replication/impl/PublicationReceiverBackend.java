package com.composum.sling.platform.staging.replication.impl;

import com.composum.platform.commons.json.JsonArrayAsIterable;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
    UpdateInfo startUpdate(@Nonnull ReplicationPaths replicationPaths)
            throws PersistenceException, LoginException, RemotePublicationReceiverException, RepositoryException;

    /**
     * Uploads one package into the temporary directory, taking note of the root path for later moving to content.
     */
    void pathUpload(@Nullable String updateId, @Nonnull String packageRootPath, @Nonnull InputStream inputStream)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, IOException, ConfigurationException;

    /**
     * Moves the content to the content directory and deletes the given paths, thus finalizing the update. The
     * temporary directory is then deleted.
     */
    void commit(@Nonnull String updateId, @Nonnull Set<String> deletedPaths,
                @Nonnull Iterable<ChildrenOrderInfo> childOrderings, String newReleaseChangeId)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, PersistenceException;

    /**
     * Retrieves a list of {@link VersionableInfo} from the {jsonInputStream}, checks these against the content and
     * returns the paths where differences in the version number exist / paths that do not exist.
     */
    @Nonnull
    List<String> compareContent(@Nullable ReplicationPaths replicationPaths, @Nullable String updateId,
                                @Nonnull BufferedReader jsonInputReader)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, IOException;

    /**
     * Aborts the update operation and deletes the temporary directory.
     */
    void abort(@Nullable String updateId)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, PersistenceException;

    /**
     * Gets general info about a release without starting an update.
     */
    @Nullable
    UpdateInfo releaseInfo(@Nonnull ReplicationPaths replicationPaths) throws LoginException, RepositoryException;

    /**
     * Reads childorderings as {@link ChildrenOrderInfo} and compares these to whatever we have in our repository,
     * and returns the paths where it's different.
     */
    @Nonnull
    List<String> compareChildorderings(@Nonnull ReplicationPaths replicationPaths,
                                       @Nonnull Iterable<ChildrenOrderInfo> childOrderings)
            throws LoginException, RemotePublicationReceiverException, RepositoryException;

    /**
     * Reads node attribute information {@link NodeAttributeComparisonInfo}  and compares these to whatever we have
     * in our repository, and returns the paths where it's different.
     */
    @Nonnull
    List<String> compareAttributes(@Nonnull ReplicationPaths replicationPaths,
                                   @Nonnull Iterable<NodeAttributeComparisonInfo> attributeInfos) throws LoginException, RemotePublicationReceiverException;

    /**
     * Generates a {@link VersionableTree} from which one can retrieve the {@link VersionableInfo}s below a set of paths.
     *
     * @param resolver the resolver used; it's necessary to route that in since it canot be closed within the method since
     *                 VersionableTree contains resources from this resolver
     */
    VersionableTree contentStatus(@Nonnull ReplicationPaths replicationPaths, @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver);

    public class RemotePublicationReceiverException extends Exception {

        public enum RetryAdvice {
            /**
             * Temporary failure (e.g. because of concurrent modification) - can be retried immediately.
             */
            RETRY_IMMEDIATELY,
            /**
             * Permanent failure - manual intervention needed.
             */
            NO_AUTOMATIC_RETRY
        }

        private final RetryAdvice retryadvice;

        public RemotePublicationReceiverException(String message, RetryAdvice advice) {
            super(message);
            this.retryadvice = advice;
        }

        public RetryAdvice getRetryadvice() {
            return retryadvice;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("PublicationReceiverFacadeException{");
            sb.append("message='").append(getMessage()).append('\'');
            sb.append(", retryadvice=").append(retryadvice);
            sb.append('}');
            return sb.toString();
        }
    }

}

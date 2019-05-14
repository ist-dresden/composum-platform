package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * <p>
 * A service that receives activation events and can publish these - e.g. to /publish and /preview in the JCR or even remote servers.
 * There can be an arbitrary number of {@link ReplicationService}s, which decide based on the {@link ActivationEvent}s they receive
 * whether they have to do something.
 * </p>
 * <p>
 * The transmitted resources can be {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s, hierarchy nodes containing several
 * {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s or none (e.g. assets). The {@link ReplicationService}
 * has to check that on his own, if that's relevant.
 * Usually it will be Resources from a {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}
 * for the given release.
 * </p>
 * <p>
 * It's the {@link ReplicationService}'s job to decide what actions to take on that. It can assume that it receives all
 * {@link ActivationEvent}s or that the user is informed about an error (or, at least, did not get an acknowledgement of success)
 * and that it is the users responsibility to trigger a full site update to fix any errors due to missing events.
 * </p>
 * <p>
 * For a {@link ReplicationService} it is advisable to check whether resources referred by the activated / updated resources are updated, too,
 * since e.g. for assets and configurations there might not be any activation events.
 * </p>
 */
public interface ReplicationService {

    /** Information about some activated or deactivated resources. */
    interface ActivationEvent {

        /** The release in which the items have been activated or deactivated. */
        StagingReleaseManager.Release release();

        /**
         * A collection of resources that have been activated (that is, haven't been present before in this release).
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<Resource> activatedResources();

        /**
         * A collection of resources that have been updated (they have already been present in this release).
         * There might have been subresources of this that have been activated or deactivated: if a full update
         * of a whole site should be done, an {@link ActivationEvent} can be sent with an update for the site root.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<Resource> updatedResources();

        /**
         * A collection of resources that have been deactivated (that is, have been removed from this release).
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<Resource> deactivatedResources();

    }

    /**
     * This informs the replication service about an activation / deactivation / update. The publisher can decide on his own
     * whether he is responsible. The processing should be synchronous, so that the user can be notified whether it succeeded or not.
     */
    void receive(ActivationEvent activationEvent) throws ReplicationFailedException;

    /** Informs that a replication failed and a full site replication is needed. */
    class ReplicationFailedException extends Exception {
        public ReplicationFailedException(String message) {
            super(message);
        }
    }

}

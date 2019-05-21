package com.composum.sling.platform.staging;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * <p>
 * A service that receives activation events and can publish these - e.g. to /publish and /preview in the JCR or even remote servers.
 * There can be an arbitrary number of {@link ReplicationService}s, which decide based on the {@link ReleaseChangeEvent}s they receive
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
 * {@link ReleaseChangeEvent}s or that the user is informed about an error (or, at least, did not get an acknowledgement of success)
 * and that it is the users responsibility to trigger a full site update to fix any errors due to missing events.
 * </p>
 * <p>
 * For a {@link ReplicationService} it is advisable to check whether resources referred by the activated / updated resources are updated, too,
 * since e.g. for assets and configurations there might not be any activation events. Also the order of children in the parent nodes of a resource
 * might have changed.
 * </p>
 */
public interface ReplicationService {

    /** Information about some activated or deactivated resources in a release, to control replication. */
    public class ReleaseChangeEvent {

        @Nonnull
        private final StagingReleaseManager.Release release;
        private final List<String> newResources = new ArrayList<>();
        private final List<String> updatedResources = new ArrayList<>();
        private final List<String> removedResources = new ArrayList<>();

        public ReleaseChangeEvent(@Nonnull StagingReleaseManager.Release release) {
            this.release = release;
        }

        /** Gives an activation event that says "update to release root" - that is, everything has to be checked. */
        public static ReleaseChangeEvent fullUpdate(@Nonnull StagingReleaseManager.Release release) {
            ReleaseChangeEvent res = new ReleaseChangeEvent(release);
            res.updatedResources.add(release.getReleaseRoot().getPath());
            return res;
        }

        /** The release in which the items have been activated or deactivated. */
        @Nonnull
        StagingReleaseManager.Release release() {
            return release;
        }

        /**
         * A collection of resources that have been activated (that is, haven't been present before in this release).
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<String> newResources() {
            return newResources;
        }

        /**
         * A collection of resources that have been updated (they have already been present in this release).
         * There might have been subresources of this that have been activated or deactivated: if a full update
         * of a whole site should be done, an {@link ReleaseChangeEvent} can be sent with an update for the site root.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<String> updatedResources() {
            return updatedResources;
        }

        /**
         * A collection of resources that have been deactivated (that is, have been removed from this release).
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        List<String> removedResources() {
            return removedResources;
        }

        @Override
        public String toString() {
            ToStringBuilder builder = new ToStringBuilder(this);
            builder.append("release", release);
            if (!newResources.isEmpty()) builder.append("activated", newResources);
            if (!updatedResources.isEmpty()) builder.append("updated", updatedResources);
            if (!removedResources.isEmpty()) builder.append("deactivated", removedResources);
            return builder.toString();
        }

        /**
         * Takes appropriate notice when a resource is updated and during this moved from frompath to topath - these may be
         * the same or null if it vanishes / appears.
         */
        public void addMoveOrUpdate(@Nullable String frompath, @Nullable String topath) {
            if (isNotBlank(frompath) && !release.appliesToPath(frompath))
                throw new IllegalArgumentException("Src. path " + frompath + " is not in release " + release);
            if (isNotBlank(topath) && !release.appliesToPath(topath))
                throw new IllegalArgumentException("Dest. path " + frompath + " is not in release " + release);

            if (isBlank(frompath)) {
                if (isNotBlank(topath)) {
                    updatedResources.add(topath);
                }
            } else {
                if (isNotBlank(topath)) {
                    if (StringUtils.equals(frompath, topath)) {
                        updatedResources.add(topath);
                    } else {
                        removedResources.add(frompath);
                        newResources.add(topath);
                    }
                }
            }
        }

    }

    /**
     * This informs the replication service about an activation / deactivation / update. The publisher can decide on his own
     * whether he is responsible. The processing should be synchronous, so that the user can be notified whether it succeeded or not.
     */
    void receive(ReleaseChangeEvent releaseChangeEvent) throws ReplicationFailedException;

    /** Informs that a replication failed and a full site replication is needed. */
    class ReplicationFailedException extends Exception {

        private final ReleaseChangeEvent releaseChangeEvent;

        public ReplicationFailedException(String message, ReleaseChangeEvent releaseChangeEvent) {
            super(message);
            this.releaseChangeEvent = releaseChangeEvent;
        }

        public ReplicationFailedException(String message, RuntimeException e, ReleaseChangeEvent releaseChangeEvent) {
            super(message, e);
            this.releaseChangeEvent = releaseChangeEvent;
        }

        public ReleaseChangeEvent getReleaseChangeEvent() {
            return releaseChangeEvent;
        }
    }

}

package com.composum.sling.platform.staging;

import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * <p>
 * A service that receives activation events and can publish these - e.g. to /publish and /preview in the JCR or even remote servers.
 * There can be an arbitrary number of {@link ReleaseChangeEventListener}s, which decide based on the {@link ReleaseChangeEvent}s they receive
 * whether they have to do something.
 * </p>
 * <p>
 * The transmitted resources can be {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s, hierarchy nodes containing several
 * {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s or none (e.g. assets). The {@link ReleaseChangeEventListener}
 * has to check that on his own, if that's relevant.
 * Usually it will be Resources from a {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}
 * for the given release.
 * </p>
 * <p>
 * It's the {@link ReleaseChangeEventListener}'s job to decide what actions to take on that. It can assume that it receives all
 * {@link ReleaseChangeEvent}s or that the user is informed about an error (or, at least, did not get an acknowledgement of success)
 * and that it is the users responsibility to trigger a full site update to fix any errors due to missing events.
 * </p>
 * <p>
 * For a {@link ReleaseChangeEventListener} it is advisable to check whether resources referred by the activated / updated resources are updated, too,
 * since e.g. for assets and configurations there might not be any activation events. Also the order of children in the parent nodes of a resource
 * might have changed.
 * </p>
 */
public interface ReleaseChangeEventListener {


    /**
     * This informs the replication service about an activation / deactivation / update. The publisher can decide on his own
     * whether he is responsible. The processing should be synchronous, so that the user can be notified whether it succeeded or not.
     */
    void receive(ReleaseChangeEvent releaseChangeEvent) throws ReplicationFailedException;

    /** Information about some activated or deactivated resources in a release, to control replication. */
    public final class ReleaseChangeEvent {

        @Nonnull
        private final StagingReleaseManager.Release release;
        private final Set<String> newResources = new LinkedHashSet<>();
        private final Set<String> updatedResources = new LinkedHashSet<>();
        private final Map<String, String> movedResources = new LinkedHashMap<>();
        private final Set<String> removedResources = new LinkedHashSet<>();
        private boolean finalized;

        public ReleaseChangeEvent(@Nonnull StagingReleaseManager.Release release) {
            this.release = release;
        }

        public boolean isEmpty() {
            return newResources.isEmpty() && updatedResources.isEmpty() && movedResources.isEmpty() && removedResources.isEmpty();
        }

        /** Gives an activation event that says "update to release root" - that is, everything has to be checked. */
        public static ReleaseChangeEvent fullUpdate(@Nonnull StagingReleaseManager.Release release) {
            ReleaseChangeEvent res = new ReleaseChangeEvent(release);
            res.updatedResources.add(release.getReleaseRoot().getPath());
            return res;
        }

        /** The release in which the items have been activated or deactivated. */
        @Nonnull
        public StagingReleaseManager.Release release() {
            return release;
        }

        /**
         * A collection of resources that have been activated (that is, haven't been present before in this release).
         * This excludes resources that are in {@link #movedResources()} - you can use {@link #newOrMovedResources()} for that.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        public Set<String> newResources() {
            return Collections.unmodifiableSet(newResources);
        }

        /**
         * A collection of resources that have been activated (that is, haven't been present before in this release),
         * including resources that newly appear because they are in {@link #movedResources()}.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        public Set<String> newOrMovedResources() {
            return Collections.unmodifiableSet(SetUtils.union(newResources, new LinkedHashSet<>(movedResources.values())));
        }

        /**
         * A collection of resources that have been updated (they have already been present in this release).
         * There might have been subresources of this that have been activated or deactivated: if a full update
         * of a whole site should be done, an {@link ReleaseChangeEvent} can be sent with an update for the site root.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        public Set<String> updatedResources() {
            return Collections.unmodifiableSet(updatedResources);
        }

        /**
         * A collection of resources that have been deactivated (that is, have been removed from this release).
         * This excludes resources that are in {@link #movedResources()} - you can use {@link #newOrMovedResources()} for that.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        @Nonnull
        public Set<String> removedResources() {
            return Collections.unmodifiableSet(removedResources);
        }

        /**
         * A collection of resources that have been activated (that is, haven't been present before in this release),
         * including resources that disappear at one place because they are in {@link #movedResources()}.
         * For all resources we have that {@link com.composum.sling.platform.staging.StagingReleaseManager.Release#appliesToPath(String)}.
         */
        public Set<String> removedOrMovedResources() {
            return Collections.unmodifiableSet(SetUtils.union(removedResources, movedResources.keySet()));
        }

        /** Maps absolute paths of resources moved from one place to the place they are moved to. */
        public Map<String, String> movedResources() {
            return Collections.unmodifiableMap(movedResources);
        }

        @Override
        public String toString() {
            ToStringBuilder builder = new ToStringBuilder(this);
            builder.append("release", release);
            if (!newResources.isEmpty()) { builder.append("new", newResources); }
            if (!updatedResources.isEmpty()) { builder.append("updated", updatedResources); }
            if (!removedResources.isEmpty()) { builder.append("removed", removedResources); }
            return builder.toString();
        }

        /**
         * Takes appropriate notice when a resource is updated and during this moved from frompath to topath - these may be
         * the same or null if it vanishes / appears.
         */
        public void addMoveOrUpdate(@Nullable String frompath, @Nullable String topath) {
            if (finalized) { throw new IllegalStateException("Already finalized - cannot be changed anymore"); }
            if (isNotBlank(frompath) && !release.appliesToPath(frompath)) {
                throw new IllegalArgumentException("Src. path " + frompath + " is not in release " + release);
            }
            if (isNotBlank(topath) && !release.appliesToPath(topath)) {
                throw new IllegalArgumentException("Dest. path " + frompath + " is not in release " + release);
            }
            if (StringUtils.startsWith(frompath, StagingConstants.RELEASE_ROOT_PATH)) {
                throw new IllegalArgumentException("Bug: src. path " + frompath + " is in release copy of " + release);
            }
            if (StringUtils.startsWith(topath, StagingConstants.RELEASE_ROOT_PATH)) {
                throw new IllegalArgumentException("Bug: src. path " + topath + " is in release copy of " + release);
            }

            if (isNotBlank(frompath)) {
                if (isNotBlank(topath)) {
                    if (StringUtils.equals(frompath, topath)) {
                        updatedResources.add(topath);
                    } else {
                        movedResources.put(frompath, topath);
                    }
                } else {
                    removedResources.add(frompath);
                }
            } else { // blank frompath
                if (isNotBlank(topath)) {
                    newResources.add(topath);
                } else {
                    throw new IllegalArgumentException("Bug: moving null to null?");
                }
            }
        }

        /** Cannot be changed through {@link #addMoveOrUpdate(String, String)} anymore. */
        @Override
        public void finalize() {
            if (release == null) { throw new IllegalStateException("No release? " + toString()); }
            finalized = true;
        }

    }

    /**
     * Informs that a replication failed and a full site replication is needed. If there are several failures,
     * those are appended to {@link ReplicationFailedException#getSuppressed()}.
     */
    class ReplicationFailedException extends Exception {

        private final ReleaseChangeEvent releaseChangeEvent;

        public ReplicationFailedException(String message, ReleaseChangeEvent releaseChangeEvent) {
            super(message);
            this.releaseChangeEvent = releaseChangeEvent;
        }

        public ReplicationFailedException(String message, Exception e, ReleaseChangeEvent releaseChangeEvent) {
            super(message, e);
            this.releaseChangeEvent = releaseChangeEvent;
        }

        public ReleaseChangeEvent getReleaseChangeEvent() {
            return releaseChangeEvent;
        }
    }

}

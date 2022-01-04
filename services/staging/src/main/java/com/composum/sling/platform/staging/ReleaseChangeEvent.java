package com.composum.sling.platform.staging;

import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.*;

import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Information about some activated or deactivated resources in a release, to control replication.
 */
public final class ReleaseChangeEvent {

    @NotNull
    private final Release release;
    private final Set<String> newResources = new LinkedHashSet<>();
    private final Set<String> updatedResources = new LinkedHashSet<>();
    private final Map<String, String> movedResources = new LinkedHashMap<>();
    private final Set<String> removedResources = new LinkedHashSet<>();
    /**
     * If true, the receiver should ignore the release change numbers and do a full check.
     */
    private boolean forceCheck;
    private boolean finalized;

    public ReleaseChangeEvent(@NotNull Release release) {
        this.release = release;
    }

    public boolean isEmpty() {
        return newResources.isEmpty() && updatedResources.isEmpty() && movedResources.isEmpty() && removedResources.isEmpty();
    }

    /**
     * Gives an activation event that says "update to release root" - that is, everything has to be checked.
     */
    public static ReleaseChangeEvent fullUpdate(@NotNull Release release) {
        ReleaseChangeEvent res = new ReleaseChangeEvent(release);
        res.updatedResources.add(release.getReleaseRoot().getPath());
        return res;
    }

    /**
     * The release in which the items have been activated or deactivated.
     */
    @NotNull
    public Release release() {
        return release;
    }

    /**
     * A collection of resources that have been activated (that is, haven't been present before in this release).
     * This excludes resources that are in {@link #movedResources()} - you can use {@link #newOrMovedResources()} for that.
     * For all resources we have that {@link Release#appliesToPath(String)}.
     */
    @NotNull
    public Set<String> newResources() {
        return Collections.unmodifiableSet(newResources);
    }

    /**
     * A collection of resources that have been activated (that is, haven't been present before in this release),
     * including resources that newly appear because they are in {@link #movedResources()}.
     * For all resources we have that {@link Release#appliesToPath(String)}.
     */
    public Set<String> newOrMovedResources() {
        return Collections.unmodifiableSet(SetUtils.union(newResources, new LinkedHashSet<>(movedResources.values())));
    }

    /**
     * A collection of resources that have been updated (they have already been present in this release).
     * There might have been subresources of this that have been activated or deactivated: if a full update
     * of a whole site should be done, an {@link ReleaseChangeEvent} can be sent with an update for the site root.
     * For all resources we have that {@link Release#appliesToPath(String)}.
     */
    @NotNull
    public Set<String> updatedResources() {
        return Collections.unmodifiableSet(updatedResources);
    }

    /**
     * A collection of resources that have been deactivated (that is, have been removed from this release).
     * This excludes resources that are in {@link #movedResources()} - you can use {@link #newOrMovedResources()} for that.
     * For all resources we have that {@link Release#appliesToPath(String)}.
     */
    @NotNull
    public Set<String> removedResources() {
        return Collections.unmodifiableSet(removedResources);
    }

    /**
     * A collection of resources that have been activated (that is, haven't been present before in this release),
     * including resources that disappear at one place because they are in {@link #movedResources()}.
     * For all resources we have that {@link Release#appliesToPath(String)}.
     */
    public Set<String> removedOrMovedResources() {
        return Collections.unmodifiableSet(SetUtils.union(removedResources, movedResources.keySet()));
    }

    /**
     * Maps absolute paths of resources moved from one place to the place they are moved to.
     */
    public Map<String, String> movedResources() {
        return Collections.unmodifiableMap(movedResources);
    }

    /**
     * If set to true, the receiver should ignore the release change numbers and do a full check.
     *
     * @return this for builder style chaining.
     */
    public ReleaseChangeEvent setForceCheck(boolean forceCheck) {
        if (finalized) {
            throw new IllegalStateException("Already finalized - cannot be changed anymore");
        }
        this.forceCheck = forceCheck;
        return this;
    }

    /**
     * If true, the receiver should ignore the release change numbers and do a full check.
     */
    public boolean getForceCheck() {
        return forceCheck;
    }

    @Override
    public String toString() {
        ToStringBuilder builder = new ToStringBuilder(this);
        builder.append("release", release);
        if (!newResources.isEmpty()) {
            builder.append("new", newResources);
        }
        if (!updatedResources.isEmpty()) {
            builder.append("updated", updatedResources);
        }
        if (!removedResources.isEmpty()) {
            builder.append("removed", removedResources);
        }
        return builder.toString();
    }

    /**
     * Takes appropriate notice when a resource is updated and during this moved from frompath to topath - these may be
     * the same or null if it vanishes / appears. {frompath} and {topath} may be absolute paths into the release,
     * relative paths within the release or absolute paths into the release workspace.
     */
    public void addMoveOrUpdate(@Nullable String rawFrompath, @Nullable String rawTopath) {
        if (finalized) {
            throw new IllegalStateException("Already finalized - cannot be changed anymore");
        }
        String frompath = rawFrompath != null ? release.absolutePath(rawFrompath) : null;
        String topath = rawTopath != null ? release.absolutePath(rawTopath) : null;
        if (isNotBlank(frompath)) {
            if (isNotBlank(topath)) {
                if (StringUtils.equals(frompath, topath) && !newResources.contains(topath)) {
                    addPath(updatedResources, topath);
                } else {
                    movedResources.put(frompath, topath);
                }
            } else {
                addPath(removedResources, frompath);
            }
        } else { // blank frompath
            if (isNotBlank(topath)) {
                addPath(newResources, topath);
            } else {
                throw new IllegalArgumentException("Bug: moving null to null?");
            }
        }
    }

    /**
     * Adds a path but minimizes the set in that if some path is in there, no subpath of it is there, too, since
     * that's subsumed.
     */
    protected void addPath(Set<String> pathset, String path) {
        if (pathset.stream().noneMatch((existing) -> isSameOrDescendant(existing, path))) {
            pathset.removeIf((existing) -> isSameOrDescendant(path, existing));
            pathset.add(path);
        }
    }

    /**
     * Becomes immutable: cannot be changed through {@link #addMoveOrUpdate(String, String)} anymore.
     */
    public void finish() {
        if (release == null) {
            throw new IllegalStateException("No release? " + toString());
        }
        finalized = true;
    }

}

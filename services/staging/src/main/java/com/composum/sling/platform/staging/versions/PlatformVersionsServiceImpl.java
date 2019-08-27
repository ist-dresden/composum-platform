package com.composum.sling.platform.staging.versions;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.VersionReference;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.CONTENT_NODE;
import static com.composum.sling.core.util.CoreConstants.PROP_CREATED;
import static com.composum.sling.core.util.CoreConstants.PROP_LAST_MODIFIED;
import static com.composum.sling.core.util.CoreConstants.TYPE_LAST_MODIFIED;
import static com.composum.sling.core.util.CoreConstants.TYPE_VERSIONABLE;
import static com.composum.sling.core.util.SlingResourceUtil.getPath;
import static com.composum.sling.core.util.SlingResourceUtil.getPaths;
import static java.util.Collections.singletonList;

/**
 * This is the default implementation of the {@link PlatformVersionsService} - see there.
 *
 * @see PlatformVersionsService
 */
@Component(
        service = {PlatformVersionsService.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Versions Service"
        }
)
public class PlatformVersionsServiceImpl implements PlatformVersionsService {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformVersionsServiceImpl.class);

    @Reference
    protected StagingReleaseManager releaseManager;

    @Nonnull
    @Override
    public StagingReleaseManager.Release getDefaultRelease(@Nonnull Resource versionable) {
        return releaseManager.findRelease(versionable, StagingConstants.CURRENT_RELEASE);
    }

    protected StagingReleaseManager.Release getRelease(Resource versionable, String releaseKey) {
        if (StringUtils.isBlank(releaseKey)) { return getDefaultRelease(versionable); }
        return releaseManager.findRelease(versionable, releaseKey);
    }

    /**
     * If the versionable is a {@link com.composum.sling.core.util.CoreConstants#TYPE_VERSIONABLE} it is returned,
     * else we check whether it has a versionable {@link com.composum.sling.core.util.CoreConstants#CONTENT_NODE} and return that,
     * otherwise we throw up as this is an invalid argument.
     *
     * @return a {@link ResourceHandle} that is valid and is versionable
     * @throws IllegalArgumentException if {versionable} doesn't denote a valid versionable
     */
    protected ResourceHandle normalizeVersionable(Resource versionable) throws IllegalArgumentException {
        ResourceHandle handle = ResourceHandle.use(versionable);
        if (ResourceUtil.isNonExistingResource(versionable)) { return handle; }
        if (handle.isValid() && handle.isOfType(TYPE_VERSIONABLE)) { return handle; }
        ResourceHandle contentResource = handle.getContentResource();
        if (contentResource.isValid() && contentResource.isOfType(TYPE_VERSIONABLE)) { return contentResource; }
        throw new IllegalArgumentException("Not a versionable nor something with a versionable " + CONTENT_NODE + " : " + getPath(versionable));
    }

    @Override
    @Nullable
    public StatusImpl getStatus(@Nonnull Resource rawVersionable, @Nullable String releaseKey) {
        try {
            ResourceHandle versionable = normalizeVersionable(rawVersionable);
            ReleasedVersionable workspaced = ReleasedVersionable.forBaseVersion(versionable);
            StagingReleaseManager.Release release = getRelease(versionable, releaseKey);
            ReleasedVersionable released = releaseManager.findReleasedVersionable(release, versionable);
            return new StatusImpl(workspaced, release, released);
        } catch (StagingReleaseManager.ReleaseRootNotFoundException | IllegalArgumentException e) {
            return null;
        }
    }

    @Nonnull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @Nonnull Resource rawVersionable, @Nullable String versionUuid) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        ActivationResult activationResult = activateSingle(releaseKey, rawVersionable, versionUuid);
        return activationResult;
    }

    @Nonnull
    protected ActivationResult activateSingle(@Nullable String releaseKey, @Nonnull Resource rawVersionable, @Nullable String versionUuid) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        // XXX implement deletion with NonExistingResource .

        LOG.info("Requested activation {} in release {} to version {}", getPath(rawVersionable), releaseKey, versionUuid);
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        maybeCheckpoint(versionable);
        StatusImpl oldStatus = getStatus(versionable, releaseKey);
        StagingReleaseManager.Release release = Objects.requireNonNull(oldStatus.getPreviousRelease());
        ActivationResult activationResult = new ActivationResult(release);

        boolean moveRequested = oldStatus.getNextVersionable() != null && oldStatus.getPreviousVersionable() != null &&
                !StringUtils.equals(oldStatus.getNextVersionable().getRelativePath(), oldStatus.getPreviousVersionable().getRelativePath());

        if (oldStatus.getActivationState() != ActivationState.activated || moveRequested) {

            ReleasedVersionable updateRV = oldStatus.getNextVersionable().clone();
            if (StringUtils.isNotBlank(versionUuid) && !StringUtils.equals(versionUuid, updateRV.getVersionUuid())) {
                if (oldStatus.getPreviousVersionable() != null) {
                    updateRV = oldStatus.getPreviousVersionable().clone(); // make sure we don't move the document around
                }
                updateRV.setVersionUuid(versionUuid);
            }
            updateRV.setActive(true);

            Map<String, SiblingOrderUpdateStrategy.Result> orderUpdateMap = releaseManager.updateRelease(release, singletonList(updateRV));
            activationResult.getChangedPathsInfo().putAll(orderUpdateMap);

            StatusImpl newStatus = getStatus(versionable, releaseKey);
            Validate.isTrue(newStatus.getVersionReference() != null, "Bug: not contained in release after activation: %s", newStatus);
            Validate.isTrue(newStatus.getActivationState() == ActivationState.activated, "Bug: not active after activation: %s", newStatus);

            switch (oldStatus.getActivationState()) {
                case initial:
                case deactivated: // on deactivated it's a bit unclear whether this should be new or moved or something.
                    activationResult.getNewPaths().add(release.absolutePath(updateRV.getRelativePath()));
                    break;
                case activated: // must have been a move
                case modified:
                    if (moveRequested) {
                        activationResult.getMovedPaths().put(
                                release.absolutePath(oldStatus.getPreviousVersionable().getRelativePath()),
                                release.absolutePath(oldStatus.getNextVersionable().getRelativePath())
                        );
                    }
                    break;
                default:
                    throw new IllegalStateException("Bug: oldStatus = " + oldStatus);
            }

            LOG.info("Activated {} in release {} to version {}", getPath(rawVersionable), release.getNumber(), newStatus.getNextVersionable().getVersionUuid());
        } else {
            LOG.info("Already activated in release {} : {}", release.getNumber(), getPath(rawVersionable));
        }
        return activationResult;
    }

    @Nonnull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        ActivationResult result = new ActivationResult(null);
        List<Resource> normalizedCheckedinVersionables = new ArrayList<>();
        for (Resource rawVersionable : versionables) {
            ResourceHandle versionable = normalizeVersionable(rawVersionable);
            maybeCheckpoint(versionable);
            normalizedCheckedinVersionables.add(versionable);
        }
        for (Resource versionable : normalizedCheckedinVersionables) {
            result = result.merge(activate(releaseKey, versionable, null));
        }
        return result;
    }

    /** Checks whether the last modification date is later than the last checkin date. */
    protected void maybeCheckpoint(ResourceHandle versionable) throws RepositoryException {
        VersionManager versionManager = versionable.getNode().getSession().getWorkspace().getVersionManager();
        Version baseVersion = versionManager.getBaseVersion(versionable.getPath());
        VersionHistory versionHistory = versionManager.getVersionHistory(versionable.getPath());
        if (!versionable.isOfType(TYPE_LAST_MODIFIED)) {
            LOG.warn("Mixin {} is required for proper function, but missing in {}", TYPE_LAST_MODIFIED, versionable.getPath());
        }
        Calendar lastModified = versionable.getProperty(PROP_LAST_MODIFIED, Calendar.class);
        if (lastModified == null) { lastModified = versionable.getProperty(PROP_CREATED, Calendar.class); }
        Version rootVersion = versionHistory.getRootVersion();
        if (baseVersion.isSame(rootVersion) ||
                lastModified != null && lastModified.after(baseVersion.getCreated())) {
            versionManager.checkpoint(versionable.getPath());
        }
    }

    @Override
    public void deactivate(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        LOG.info("Requested deactivation {} in {}", getPaths(versionables), releaseKey);
        for (Resource versionable : versionables) {
            StatusImpl status = getStatus(versionable, releaseKey);
            switch (status.getActivationState()) {
                case modified:
                case activated:
                    LOG.info("Deactivating in {} : {}", status.getPreviousRelease().getNumber(), getPath(versionable));
                    ReleasedVersionable releasedVersionable = status.getPreviousVersionable();
                    releasedVersionable.setActive(false);
                    releaseManager.updateRelease(status.getPreviousRelease(), singletonList(releasedVersionable));
                    break;
                case initial:
                case deactivated:
                    LOG.info("Not deactivating in {} since not active: {}", status.getPreviousRelease().getNumber(), getPath(versionable));
                    break;
                default:
            }
        }
    }

    @Override
    @Nonnull
    public ActivationResult revert(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        if (versionables == null || versionables.isEmpty()) { return new ActivationResult(null); }
        Resource firstVersionable = versionables.get(0);
        StagingReleaseManager.Release release = getRelease(firstVersionable, releaseKey);
        StagingReleaseManager.Release previousRelease = release.getPreviousRelease();
        ActivationResult result = new ActivationResult(release);

        String previousReleaseNumber = previousRelease != null ? previousRelease.getNumber() : null;
        LOG.info("Requested reverting in {} to previous release {} : {}", releaseKey, previousReleaseNumber, getPaths(versionables));

        for (Resource rawVersionable : versionables) {
            if (!release.appliesToPath(rawVersionable.getPath())) {
                throw new IllegalArgumentException("Arguments from different releases: " + getPaths(versionables));
            }
            ResourceHandle versionable = normalizeVersionable(rawVersionable);

            ReleasedVersionable rvInRelease = releaseManager.findReleasedVersionable(release, versionable);
            if (rvInRelease == null) {
                LOG.warn("Not reverting in {} since not present: {}", release.getNumber(), getPath(versionable));
                continue;
            }
            ReleasedVersionable rvInPreviousRelease = previousRelease != null ? releaseManager.findReleasedVersionable(previousRelease, versionable) : null;
            LOG.info("Reverting in {} from {} : {}", release, previousReleaseNumber, rvInPreviousRelease);

            if (rvInPreviousRelease == null) { // delete it since it wasn't in the previous release or there is no previous release
                ReleasedVersionable update = rvInRelease.clone();
                update.setVersionUuid(null); // delete request
                result.getRemovedPaths().add(release.absolutePath(rvInRelease.getRelativePath()));
                Map<String, SiblingOrderUpdateStrategy.Result> info = releaseManager.updateRelease(release, singletonList(update));
                result.getChangedPathsInfo().putAll(info);
            } else { // if (rvInPreviousRelease != null) -> update to previous state
                Map<String, SiblingOrderUpdateStrategy.Result> info = releaseManager.updateRelease(release, singletonList(rvInPreviousRelease));
                result.getChangedPathsInfo().putAll(info);
                if (!StringUtils.equals(rvInPreviousRelease.getRelativePath(), rvInRelease.getRelativePath())) {
                    result.getMovedPaths().put(
                            release.absolutePath(rvInRelease.getRelativePath()),
                            release.absolutePath(rvInPreviousRelease.getRelativePath())
                    );
                }
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * What we actually do here is look for labelled versions - all released versions are labelled.
     */
    @Override
    public void purgeVersions(@Nonnull Resource rawVersionable) throws RepositoryException {
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        Validate.isTrue(ResourceHandle.use(versionable).isOfType(TYPE_VERSIONABLE), "Argument must be a %s : %s", TYPE_VERSIONABLE, getPath(versionable));
        VersionManager versionManager = ResourceHandle.use(versionable).getNode().getSession().getWorkspace().getVersionManager();
        VersionHistory versionHistory = versionManager.getVersionHistory(versionable.getPath());
        String[] versionLabels = versionHistory.getVersionLabels();
        Set<String> labelledVersions = Arrays.stream(versionLabels)
                .map(ExceptionUtil.sneakExceptions(versionHistory::getVersionByLabel))
                .map(ExceptionUtil.sneakExceptions(Version::getName))
                .collect(Collectors.toSet());
        List<Version> allversions = JcrIteratorUtil.asStream(versionHistory.getAllLinearVersions()).collect(Collectors.toList());
        Collections.reverse(allversions);
        boolean afterLabelledVersion = true;
        for (Version version : allversions) {
            if (afterLabelledVersion && !labelledVersions.contains(version.getName())) { continue; }
            afterLabelledVersion = false;
            if (!labelledVersions.contains(version.getName()) && !versionHistory.getRootVersion().isSame(version)) {
                versionHistory.removeVersion(version.getName());
            }
        }
    }

    @Nonnull
    @Override
    public ResourceFilter releaseAsResourceFilter(@Nonnull Resource resourceInRelease, @Nullable String releaseKey, @Nullable ReleaseMapper releaseMapper, @Nullable ResourceFilter additionalFilter) {
        StagingReleaseManager.Release release = getRelease(resourceInRelease, releaseKey);
        ResourceResolver resolver = releaseManager.getResolverForRelease(release, releaseMapper, false);
        return new ResolvedResourceFilter(resolver, release.toString(), additionalFilter);
    }

    @Nonnull
    @Override
    public List<Status> findReleaseChanges(@Nonnull StagingReleaseManager.Release release) throws RepositoryException {
        List<Status> result = new ArrayList<>();

        List<ReleasedVersionable> releaseContent = releaseManager.listReleaseContents(release);
        Map<String, ReleasedVersionable> historyIdToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        StagingReleaseManager.Release previousRelease = release.getPreviousRelease();
        List<ReleasedVersionable> previousContent = previousRelease != null ? releaseManager.listReleaseContents(previousRelease) : Collections.emptyList();
        Map<String, ReleasedVersionable> historyIdToPrevious = previousContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        List<ReleasedVersionable> workspaceContent = releaseManager.listWorkspaceContents(release.getReleaseRoot());
        Map<String, ReleasedVersionable> historyIdToWorkspace = workspaceContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        for (String versionHistoryId : SetUtils.union(historyIdToRelease.keySet(), historyIdToPrevious.keySet())) {
            ReleasedVersionable releasedVersionable = historyIdToRelease.get(versionHistoryId);
            ReleasedVersionable previouslyReleased = historyIdToPrevious.get(versionHistoryId);
            if (!Objects.equals(previouslyReleased, releasedVersionable)) {
                Status status = new StatusImpl(release, releasedVersionable, previousRelease, previouslyReleased,
                        historyIdToWorkspace.get(versionHistoryId));
                result.add(status);
            }
        }

        return result;
    }

    @Nonnull
    @Override
    public List<Status> findWorkspaceChanges(@Nonnull StagingReleaseManager.Release release) {
        List<Status> result = new ArrayList<>();

        List<ReleasedVersionable> releaseContent = releaseManager.listReleaseContents(release);
        Map<String, ReleasedVersionable> historyIdToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        List<ReleasedVersionable> workspaceContent = releaseManager.listWorkspaceContents(release.getReleaseRoot());
        Map<String, ReleasedVersionable> historyIdToWorkspace = workspaceContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        for (String versionHistoryId : SetUtils.union(historyIdToRelease.keySet(), historyIdToWorkspace.keySet())) {
            ReleasedVersionable releasedVersionable = historyIdToRelease.get(versionHistoryId);
            ReleasedVersionable workspaceVersionable = historyIdToWorkspace.get(versionHistoryId);
            Status status = new StatusImpl(workspaceVersionable, release, releasedVersionable);
            if (status.getActivationState() == ActivationState.modified || !Objects.equals(workspaceVersionable, releasedVersionable)) {
                result.add(status);
            }
        }

        return result;
    }


    /**
     * Implementation of {@link com.composum.sling.platform.staging.versions.PlatformVersionsService.Status} that describes the status of
     * a resource in the workspace in relation to a release.
     */
    protected static class StatusImpl implements Status {
        @Nullable
        protected final StagingReleaseManager.Release nextRelease;
        @Nullable
        protected final ReleasedVersionable nextVersionable;
        @Nullable
        protected final StagingReleaseManager.Release previousRelease;
        @Nullable
        protected final ReleasedVersionable previousVersionable;
        @Nullable // null if deleted in workspace
        protected final ResourceHandle workspaceResource;
        @Nullable // null if not in release
        protected final VersionReference versionReference;
        @Nonnull
        protected final ActivationState activationState;

        /** Creates a StatusImpl that informs about the status of a versionable in the workspace in comparison to a release. */
        public StatusImpl(@Nullable ReleasedVersionable workspaceVersionable, @Nonnull StagingReleaseManager.Release release, @Nullable ReleasedVersionable releasedVersionable) {
            this.previousRelease = Objects.requireNonNull(release);
            this.previousVersionable = releasedVersionable;
            this.nextVersionable = workspaceVersionable;
            this.nextRelease = null;
            Resource currentResourceRaw = workspaceVersionable != null ? release.getReleaseRoot().getChild(workspaceVersionable.getRelativePath()) : null;
            workspaceResource = ResourceHandle.use(currentResourceRaw);
            if (workspaceVersionable != null && workspaceResource != null && !workspaceResource.isValid()) // "not null but not valid" ... strange.
            { throw new IllegalArgumentException("Invalid current resource " + release + " - " + workspaceVersionable); }
            versionReference = releasedVersionable != null ? release.versionReference(releasedVersionable.getRelativePath()) : null;

            // XXX check this
            if (releasedVersionable == null || versionReference == null) {
                activationState = ActivationState.initial;
            } else if (!releasedVersionable.isActive()) {
                activationState = ActivationState.deactivated;
            } else if (workspaceVersionable == null || !Objects.equals(workspaceVersionable, releasedVersionable)) {
                activationState = ActivationState.modified;
            } else if (versionReference.getLastActivated() == null ||
                    getLastModified() != null && getLastModified().after(versionReference.getLastActivated())) {
                activationState = ActivationState.modified;
            } else { activationState = ActivationState.activated; }
        }

        /**
         * Compares the versionable from release to previous release.
         * This should only be called if released is not equal to previouslyReleased since there is no state for equal things.
         */
        public StatusImpl(@Nonnull StagingReleaseManager.Release nextRelease, @Nullable ReleasedVersionable nextVersionable,
                          @Nullable StagingReleaseManager.Release previousRelease, @Nullable ReleasedVersionable previousVersionable,
                          @Nullable ReleasedVersionable workspace) {
            this.nextRelease = Objects.requireNonNull(nextRelease);
            this.nextVersionable = nextVersionable;
            this.previousRelease = previousRelease;
            this.previousVersionable = previousVersionable;
            this.versionReference = nextVersionable != null ? nextRelease.versionReference(nextVersionable.getRelativePath()) : null;

            boolean active = versionReference != null && versionReference.isActive();
            boolean previouslyActive = previousVersionable != null && previousVersionable.isActive();

            // XXX check this
            if (versionReference == null && previousVersionable == null) {
                activationState = ActivationState.initial;
            } else if (!active) {
                activationState = ActivationState.deactivated; // even if modified
            } else if (!previouslyActive && active) {
                activationState = ActivationState.activated; // even if modified
            } else // both previously and now active.
            // we take modified since otherwise this constructor shouldn't be called and we have no alternative here.
            { activationState = ActivationState.modified; }

            Resource workspaceResourceRaw = workspace != null ? nextRelease.getReleaseRoot().getChild(workspace.getRelativePath()) : null;
            workspaceResource = workspaceResourceRaw != null ? ResourceHandle.use(workspaceResourceRaw) : null;
        }

        @Nonnull
        @Override
        public ActivationState getActivationState() {
            return activationState;
        }

        @Nullable
        @Override
        public Calendar getLastModified() {
            Calendar result = null;
            if (workspaceResource != null && workspaceResource.isValid()) {
                result = workspaceResource.getProperty(CoreConstants.JCR_LASTMODIFIED, Calendar.class);
                if (result == null) {
                    result = workspaceResource.getProperty(CoreConstants.JCR_CREATED, Calendar.class);
                }
            }
            return result;
        }

        @Nullable
        @Override
        public String getLastModifiedBy() {
            String result = null;
            if (workspaceResource != null && workspaceResource.isValid()) {
                result = workspaceResource.getProperty(CoreConstants.JCR_LASTMODIFIED_BY, String.class);
                if (StringUtils.isBlank(result)) {
                    result = workspaceResource.getProperty(CoreConstants.JCR_CREATED_BY, String.class);
                }
            }
            return result;
        }

        @Nullable
        @Override
        public VersionReference getVersionReference() {
            return versionReference;
        }

        @Nonnull
        @Override
        public StagingReleaseManager.Release getNextRelease() {
            return nextRelease;
        }

        @Nullable
        @Override
        public ReleasedVersionable getNextVersionable() {
            return nextVersionable;
        }

        @Nullable
        @Override
        public StagingReleaseManager.Release getPreviousRelease() {
            return previousRelease;
        }

        @Nullable
        @Override
        public ReleasedVersionable getPreviousVersionable() {
            return previousVersionable;
        }

        @Override
        @Nullable
        public ResourceHandle getWorkspaceResource() {
            return workspaceResource;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("nextRelease", getNextRelease())
                    .append("prevRelease", getPreviousRelease())
                    .append("activationState", getActivationState())
                    .append("activationInfo", getVersionReference())
                    .append("nextVersionable", getNextVersionable())
                    .append("prevVersionableInfo", getPreviousVersionable())
                    .toString();
        }
    }

    /** A {@link ResourceFilter} that checks whether a resource exists in the given resolver. */
    public static class ResolvedResourceFilter extends ResourceFilter.AbstractResourceFilter {
        private final ResourceResolver resolver;
        private final String description;
        private final ResourceFilter additionalFilter;

        public ResolvedResourceFilter(ResourceResolver resolver, String description, @Nullable ResourceFilter additionalFilter) {
            this.resolver = resolver;
            this.description = description;
            this.additionalFilter = additionalFilter != null ? additionalFilter : ResourceFilter.ALL;
        }

        @Override
        public boolean accept(Resource resource) {
            Resource stagedResource = resolver.getResource(resource.getPath());
            return stagedResource != null && additionalFilter.accept(stagedResource);
        }

        @Override
        public boolean isRestriction() {
            return false;
        }

        @Override
        public void toString(StringBuilder builder) {
            builder.append(getClass().getSimpleName());
            builder.append("(").append(description);
            if (additionalFilter != ResourceFilter.ALL) {
                builder.append(",");
                additionalFilter.toString(builder);
            }
            builder.append(")");
        }
    }
}

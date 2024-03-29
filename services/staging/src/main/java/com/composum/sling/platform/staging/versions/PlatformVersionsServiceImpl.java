package com.composum.sling.platform.staging.versions;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.*;
import com.composum.sling.platform.staging.StagingReleaseManager.ReleaseNotFoundException;
import com.composum.sling.platform.staging.StagingReleaseManager.ReleaseRootNotFoundException;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
import java.util.function.Supplier;
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

    @NotNull
    @Override
    public Release getDefaultRelease(@NotNull Resource versionable) {
        return releaseManager.findRelease(versionable, StagingConstants.CURRENT_RELEASE);
    }

    protected Release getRelease(Resource versionable, String releaseKey) {
        if (StringUtils.isBlank(releaseKey)) {
            return getDefaultRelease(versionable);
        }
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
        if (ResourceUtil.isNonExistingResource(versionable)) { // we can't check here, so we assume the actual versionable is the content node.
            if (!versionable.getPath().endsWith("/" + CONTENT_NODE)) {
                versionable = new NonExistingResource(versionable.getResourceResolver(), versionable.getPath() + "/" + CONTENT_NODE);
            }
            return ResourceHandle.use(versionable);
        }
        ResourceHandle handle = ResourceHandle.use(versionable);
        if (ResourceUtil.isNonExistingResource(versionable)) {
            return handle;
        }
        if (handle.isValid() && handle.isOfType(TYPE_VERSIONABLE)) {
            return handle;
        }
        ResourceHandle contentResource = handle.getContentResource();
        if (contentResource.isValid() && contentResource.isOfType(TYPE_VERSIONABLE)) {
            return contentResource;
        }
        throw new IllegalArgumentException("Not a versionable nor something with a versionable " + CONTENT_NODE + " : " + getPath(versionable));
    }

    @Override
    @Nullable
    public StatusImpl getStatus(@NotNull Resource rawVersionable, @Nullable String releaseKey) {
        try {
            ResourceHandle versionable = normalizeVersionable(rawVersionable);
            Release release = getRelease(versionable, releaseKey);
            ReleasedVersionable released = releaseManager.findReleasedVersionable(release, versionable);
            if (released == null) {
                released = releaseManager.findReleasedVersionable(release, versionable.getPath());
            }
            if (!ResourceUtil.isNonExistingResource(rawVersionable)) {
                ReleasedVersionable workspaced = ReleasedVersionable.forBaseVersion(versionable);
                return new StatusImpl(workspaced, release, released);
            } else if (released != null) {
                return new StatusImpl(null, release, released);
            } else { // non existing resource = search by path, but nothing found
                return null;
            }
        } catch (ReleaseRootNotFoundException | ReleaseNotFoundException | IllegalArgumentException e) {
            LOG.warn("Could not determine status because of {}", e.toString());
            LOG.debug(e.toString(), e);
            return null;
        }
    }

    @NotNull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @NotNull List<Resource> versionables) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException {
        ActivationResult result = new ActivationResult(null);
        if (!versionables.isEmpty()) {
            List<Resource> normalizedCheckedinVersionables = new ArrayList<>();
            for (Resource rawVersionable : versionables) {
                ResourceHandle versionable = normalizeVersionable(rawVersionable);
                maybeCheckpoint(versionable);
                normalizedCheckedinVersionables.add(versionable);
            }
            Release release = getRelease(normalizedCheckedinVersionables.get(0), releaseKey);
            List<Pair<ReleasedVersionable, Supplier<ActivationResult>>> activatorList = new ArrayList<>();
            for (Resource versionable : normalizedCheckedinVersionables) {
                Pair<ReleasedVersionable, Supplier<ActivationResult>> activator = activateSingle(releaseKey, versionable, null, release);
                if (activator != null) {
                    activatorList.add(activator);
                }
            }

            List<ReleasedVersionable> releasedVersionables = activatorList.stream().map(Pair::getLeft).collect(Collectors.toList());
            Map<String, SiblingOrderUpdateStrategy.Result> orderUpdateMap = releaseManager.updateRelease(release, releasedVersionables);
            for (Pair<ReleasedVersionable, Supplier<ActivationResult>> activator : activatorList) {
                ActivationResult partialResult = activator.getRight().get();
                result = result.merge(partialResult);
            }
            result.getChangedPathsInfo().putAll(orderUpdateMap);
        }
        LOG.info("Activation of {} versionables done", versionables.size());
        return result;
    }

    @NotNull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @NotNull Resource rawVersionable, @Nullable String versionUuid)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException {
        ActivationResult activationResult;
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        maybeCheckpoint(versionable);
        Release release = getRelease(versionable, releaseKey);
        Pair<ReleasedVersionable, Supplier<ActivationResult>> activator = activateSingle(releaseKey, versionable, versionUuid, release);
        if (activator != null) {
            Map<String, SiblingOrderUpdateStrategy.Result> orderUpdateMap = releaseManager.updateRelease(release,
                    singletonList(activator.getLeft()));
            activationResult = activator.getRight().get();
            activationResult.getChangedPathsInfo().putAll(orderUpdateMap);
        } else {
            activationResult = new ActivationResult(release);
        }
        return activationResult;
    }

    /**
     * Returns a {@link ReleasedVersionable} that should be fed to {@link StagingReleaseManager#updateRelease(Release, List)}
     * and a function that updates an
     * {@link com.composum.sling.platform.staging.versions.PlatformVersionsService.ActivationResult} afterwards.
     * The reason these are separated is that all versionables that are activated should put into one updateRelease
     * to have them all available when the replication searches for references, but there is code to update the
     * result afterwards.
     */
    @Nullable
    protected Pair<ReleasedVersionable, Supplier<ActivationResult>> activateSingle(@Nullable String releaseKey,
                                                                                   @NotNull Resource versionable,
                                                                                   @Nullable String versionUuid, Release release)
            throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException {
        Pair<ReleasedVersionable, Supplier<ActivationResult>> result;
        if (!release.appliesToPath(versionable.getPath())) {
            throw new IllegalArgumentException("Activating versionable from different release: " + release + " vs. " + versionable.getPath());
        }
        LOG.info("Requested activation {} in release {} to version {}", getPath(versionable), releaseKey, versionUuid);
        StatusImpl oldStatus = getStatus(versionable, releaseKey);
        if (oldStatus == null) {
            LOG.error("Could not determine status for {}", getPath(versionable));
            return null; // TODO an exception might be better
            // but the result isn't visible so far, so that wouldn't help, expecially when many things are activated.
        }
        ActivationResult activationResult = new ActivationResult(release);

        boolean moveRequested = oldStatus.getNextVersionable() != null && oldStatus.getPreviousVersionable() != null &&
                !StringUtils.equals(oldStatus.getNextVersionable().getRelativePath(), oldStatus.getPreviousVersionable().getRelativePath());
        if (oldStatus.getActivationState() == ActivationState.deleted) {

            ReleasedVersionable updateRV = oldStatus.getPreviousVersionable().clone();
            updateRV.setActive(false);
            result = Pair.of(updateRV, () -> {
                activationResult.getRemovedPaths().add(versionable.getPath());
                LOG.info("Activated deletion of {} in release {}", versionable.getPath(), release);
                return activationResult;
            });
        } else if (oldStatus.getActivationState() != ActivationState.activated || moveRequested) {

            ReleasedVersionable updateRV = oldStatus.getNextVersionable().clone();
            if (StringUtils.isNotBlank(versionUuid) && !StringUtils.equals(versionUuid, updateRV.getVersionUuid())) {
                if (oldStatus.getPreviousVersionable() != null) {
                    updateRV = oldStatus.getPreviousVersionable().clone(); // make sure we don't move the document around
                }
                updateRV.setVersionUuid(versionUuid);
            }
            updateRV.setActive(true);
            ReleasedVersionable finalUpdateRV = updateRV;

            result = Pair.of(updateRV, () -> {
                StatusImpl newStatus = getStatus(versionable, releaseKey);
                Validate.isTrue(newStatus.getVersionReference() != null, "Bug: not contained in release after activation: %s", newStatus);
                Validate.isTrue(newStatus.getActivationState() == ActivationState.activated, "Bug: not active after activation: %s", newStatus);

                switch (oldStatus.getActivationState()) {
                    case initial:
                    case deactivated: // on deactivated it's a bit unclear whether this should be new or moved or something.
                        activationResult.getNewPaths().add(release.absolutePath(finalUpdateRV.getRelativePath()));
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

                LOG.info("Activated {} in release {} to version {}", getPath(versionable), release.getNumber(),
                        newStatus.getNextVersionable().getVersionUuid());
                return activationResult;
            });

        } else {
            LOG.info("Already activated in release {} : {}", release.getNumber(), getPath(versionable));
            result = null;
        }
        return result;
    }

    /**
     * Checks whether the last modification date is later than the last checkin date.
     */
    protected void maybeCheckpoint(ResourceHandle versionable) throws RepositoryException {
        VersionManager versionManager =
                Objects.requireNonNull(versionable.getResourceResolver().adaptTo(Session.class))
                        .getWorkspace().getVersionManager();
        if (!ResourceUtil.isNonExistingResource(versionable)) {
            Version baseVersion = versionManager.getBaseVersion(versionable.getPath());
            VersionHistory versionHistory = versionManager.getVersionHistory(versionable.getPath());
            if (!versionable.isOfType(TYPE_LAST_MODIFIED)) {
                LOG.warn("Mixin {} is required for proper function, but missing in {}", TYPE_LAST_MODIFIED, versionable.getPath());
            }
            Calendar lastModified = versionable.getProperty(PROP_LAST_MODIFIED, Calendar.class);
            if (lastModified == null) {
                lastModified = versionable.getProperty(PROP_CREATED, Calendar.class);
            }
            Version rootVersion = versionHistory.getRootVersion();
            if (baseVersion.isSame(rootVersion) ||
                    lastModified != null && lastModified.after(baseVersion.getCreated())) {
                versionManager.checkpoint(versionable.getPath());
            }
        }
    }

    @Override
    public void deactivate(@Nullable String releaseKey, @NotNull List<Resource> versionables) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException {
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
    @NotNull
    public ActivationResult revert(@NotNull ResourceResolver resolver, @Nullable String releaseKey, @NotNull List<String> versionablePaths) throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseClosedException, ReleaseChangeFailedException {
        if (versionablePaths == null || versionablePaths.isEmpty()) {
            return new ActivationResult(null);
        }
        Resource firstResource = resolver.getResource(versionablePaths.get(0));
        firstResource = firstResource != null ? firstResource :
                new NonExistingResource(resolver, versionablePaths.get(0));
        Release release = getRelease(firstResource, releaseKey);
        Release previousRelease = release.getPreviousRelease();
        ActivationResult result = new ActivationResult(release);

        String previousReleaseNumber = previousRelease != null ? previousRelease.getNumber() : null;
        LOG.info("Requested reverting in {} to previous release {} : {}", releaseKey, previousReleaseNumber, versionablePaths);

        for (String path : versionablePaths) {
            if (!release.appliesToPath(path)) {
                throw new IllegalArgumentException("Arguments from different releases: " + versionablePaths);
            }
            Resource pathResource = normalizeVersionable(new NonExistingResource(resolver, path));
            ReleasedVersionable rvInRelease = releaseManager.findReleasedVersionable(release, pathResource);
            ReleasedVersionable rvInPreviousRelease = previousRelease != null && rvInRelease != null ?
                    releaseManager.findReleasedVersionableByUuid(previousRelease, rvInRelease.getVersionHistory()) : null;
            if (rvInPreviousRelease == null && previousRelease != null) {
                rvInPreviousRelease = releaseManager.findReleasedVersionable(previousRelease, pathResource);
            }
            LOG.info("Reverting in {} from {} : {}", release, rvInRelease, rvInPreviousRelease);

            if (rvInPreviousRelease == null) { // delete it since it wasn't in the previous release or there is no previous release
                if (rvInRelease == null) {
                    LOG.warn("Path {} is neither in release {} nor it's previous release", path, release);
                    continue;
                }
                ReleasedVersionable update = rvInRelease.clone();
                update.setVersionUuid(null); // delete request
                result.getRemovedPaths().add(release.absolutePath(rvInRelease.getRelativePath()));
                Map<String, SiblingOrderUpdateStrategy.Result> info = releaseManager.updateRelease(release, singletonList(update));
                result.getChangedPathsInfo().putAll(info);
            } else { // if (rvInPreviousRelease != null) -> update to previous state
                Map<String, SiblingOrderUpdateStrategy.Result> info = releaseManager.revert(release,
                        rvInPreviousRelease.getRelativePath(), previousRelease);
                result.getChangedPathsInfo().putAll(info);
                if (rvInRelease == null) {
                    result.getNewPaths().add(release.absolutePath(rvInPreviousRelease.getRelativePath()));
                } else if (!StringUtils.equals(rvInPreviousRelease.getRelativePath(), rvInRelease.getRelativePath())) {
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
    public void purgeVersions(@NotNull Resource rawVersionable) throws RepositoryException {
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
            if (afterLabelledVersion && !labelledVersions.contains(version.getName())) {
                continue;
            }
            afterLabelledVersion = false;
            if (!labelledVersions.contains(version.getName()) && !versionHistory.getRootVersion().isSame(version)) {
                versionHistory.removeVersion(version.getName());
            }
        }
    }

    @NotNull
    @Override
    public ResourceFilter releaseAsResourceFilter(@NotNull Resource resourceInRelease, @Nullable String releaseKey, @Nullable ReleaseMapper releaseMapper, @Nullable ResourceFilter additionalFilter) {
        Release release = getRelease(resourceInRelease, releaseKey);
        ResourceResolver resolver = releaseManager.getResolverForRelease(release, releaseMapper, false);
        return new ResolvedResourceFilter(resolver, release.toString(), additionalFilter);
    }

    @NotNull
    @Override
    public List<Status> findReleaseChanges(@NotNull Release release) throws RepositoryException {
        List<Status> result = new ArrayList<>();

        List<ReleasedVersionable> releaseContent = releaseManager.listReleaseContents(release);
        Map<String, ReleasedVersionable> historyIdToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));
        Map<String, ReleasedVersionable> pathToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getRelativePath, Function.identity()));

        Release previousRelease = release.getPreviousRelease();
        List<ReleasedVersionable> previousContent = previousRelease != null ? releaseManager.listReleaseContents(previousRelease) : Collections.emptyList();
        Map<String, ReleasedVersionable> historyIdToPrevious = previousContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));
        Map<String, ReleasedVersionable> pathToPrevious = previousContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getRelativePath, Function.identity()));

        List<ReleasedVersionable> workspaceContent = releaseManager.listWorkspaceContents(release.getReleaseRoot());
        Map<String, ReleasedVersionable> historyIdToWorkspace = workspaceContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        for (String versionHistoryId : SetUtils.union(historyIdToRelease.keySet(), historyIdToPrevious.keySet())) {
            ReleasedVersionable releasedVersionable = historyIdToRelease.get(versionHistoryId);
            ReleasedVersionable previouslyReleased = historyIdToPrevious.get(versionHistoryId);
            if (releasedVersionable == null && pathToRelease.containsKey(previouslyReleased.getRelativePath())) {
                continue; // avoid counting that twice
            }
            if (previouslyReleased == null && pathToPrevious.containsKey(releasedVersionable.getRelativePath())) {
                previouslyReleased = pathToPrevious.get(releasedVersionable.getRelativePath());
            }
            if (!Objects.equals(previouslyReleased, releasedVersionable)) {
                Status status = new StatusImpl(release, releasedVersionable, previousRelease, previouslyReleased,
                        historyIdToWorkspace.get(versionHistoryId));
                result.add(status);
            }
        }

        return result;
    }

    @NotNull
    @Override
    public List<Status> findWorkspaceChanges(@NotNull Release release) {
        List<Status> result = new ArrayList<>();

        List<ReleasedVersionable> releaseContent = releaseManager.listReleaseContents(release);
        Map<String, ReleasedVersionable> historyIdToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));
        Map<String, ReleasedVersionable> pathToRelease = releaseContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getRelativePath, Function.identity()));

        List<ReleasedVersionable> workspaceContent = releaseManager.listWorkspaceContents(release.getReleaseRoot());
        Map<String, ReleasedVersionable> historyIdToWorkspace = workspaceContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));
        Map<String, ReleasedVersionable> pathToWorkspace = workspaceContent.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getRelativePath, Function.identity()));

        for (String versionHistoryId : SetUtils.union(historyIdToRelease.keySet(), historyIdToWorkspace.keySet())) {
            ReleasedVersionable workspaceVersionable = historyIdToWorkspace.get(versionHistoryId);
            ReleasedVersionable releasedVersionable = historyIdToRelease.get(versionHistoryId);
            if (workspaceVersionable == null && pathToWorkspace.containsKey(releasedVersionable.getRelativePath())) {
                continue; // two different versionables at the same path - avoid processing this twice
            }
            if (releasedVersionable == null) { // search by path
                releasedVersionable = pathToRelease.get(workspaceVersionable.getRelativePath());
            }
            Status status = new StatusImpl(workspaceVersionable, release, releasedVersionable);
            if (status.getActivationState() == ActivationState.deleted && !releasedVersionable.isActive()) {
                continue;
            }
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
        protected final Release nextRelease;
        @Nullable
        protected final ReleasedVersionable nextVersionable;
        @Nullable
        protected final Release previousRelease;
        @Nullable
        protected final ReleasedVersionable previousVersionable;
        @Nullable // null if deleted in workspace
        protected final ResourceHandle workspaceResource;
        @Nullable // null if not in release
        protected final VersionReference versionReference;
        @NotNull
        protected final ActivationState activationState;

        private transient String path;
        private transient Calendar lastModified;
        private transient String lastModifiedBy;

        /**
         * Creates a StatusImpl that informs about the status of a versionable in the workspace in comparison to a release.
         */
        public StatusImpl(@Nullable ReleasedVersionable workspaceVersionable, @NotNull Release release, @Nullable ReleasedVersionable releasedVersionable) {
            this.previousRelease = Objects.requireNonNull(release);
            this.previousVersionable = releasedVersionable;
            this.nextVersionable = workspaceVersionable;
            this.nextRelease = null;
            Resource rawWorkspaceResource = workspaceVersionable != null ? release.getReleaseRoot().getChild(workspaceVersionable.getRelativePath()) : null;
            workspaceResource = rawWorkspaceResource != null ? ResourceHandle.use(rawWorkspaceResource) : null;
            if (workspaceVersionable != null && workspaceResource != null && !workspaceResource.isValid()) { // "not null but not valid" ... strange.
                throw new IllegalArgumentException("Invalid current resource " + release + " - " + workspaceVersionable);
            }
            versionReference = releasedVersionable != null ? release.versionReference(releasedVersionable.getRelativePath()) : null;

            if (releasedVersionable == null || versionReference == null) {
                activationState = ActivationState.initial;
            } else if (workspaceVersionable == null) {
                activationState = ActivationState.deleted;
            } else { // releasedVersionable, versionReference, workspaceVersionable are not null now
                if (!releasedVersionable.isActive()) {
                    activationState = ActivationState.deactivated;
                } else if (!Objects.equals(releasedVersionable, workspaceVersionable)) {
                    activationState = ActivationState.modified;
                } else if (versionReference.getLastActivated() == null ||
                        getLastModified() != null && getLastModified().after(versionReference.getLastActivated())) {
                    activationState = ActivationState.modified;
                } else {
                    activationState = ActivationState.activated;
                }
            }
        }

        /**
         * Compares the versionable from release to previous release.
         * This should only be called if released is not equal to previouslyReleased since there is no state for equal things.
         */
        public StatusImpl(@NotNull Release nextRelease, @Nullable ReleasedVersionable nextVersionable,
                          @Nullable Release previousRelease, @Nullable ReleasedVersionable previousVersionable,
                          @Nullable ReleasedVersionable workspace) {
            this.nextRelease = Objects.requireNonNull(nextRelease);
            this.nextVersionable = nextVersionable;
            this.previousRelease = previousRelease;
            this.previousVersionable = previousVersionable;
            this.versionReference = nextVersionable != null ? nextRelease.versionReference(nextVersionable.getRelativePath()) : null;

            boolean active = versionReference != null && versionReference.isActive();
            boolean previouslyActive = previousVersionable != null && previousVersionable.isActive();

            if (versionReference == null && nextVersionable == null) {
                activationState = ActivationState.deleted;
            } else if (!active) {
                activationState = ActivationState.deactivated; // even if modified
            } else if (!previouslyActive && active) {
                activationState = ActivationState.activated;
            } else // both previously and now active.
            // we take modified since otherwise this constructor shouldn't be called and we have no alternative here.
            {
                activationState = ActivationState.modified;
            }

            Resource workspaceResourceRaw = workspace != null ? nextRelease.getReleaseRoot().getChild(workspace.getRelativePath()) : null;
            workspaceResource = workspaceResourceRaw != null ? ResourceHandle.use(workspaceResourceRaw) : null;
        }

        @Override
        public String getPath() {
            if (path == null) {
                if (workspaceResource != null) {
                    path = workspaceResource.getPath();
                } else if (versionReference != null) {
                    path = versionReference.getPath();
                }
            }
            return path;
        }

        @NotNull
        @Override
        public ActivationState getActivationState() {
            return activationState;
        }

        @Nullable
        @Override
        public Calendar getLastModified() {
            if (lastModified == null) {
                if (workspaceResource != null && workspaceResource.isValid()) {
                    lastModified = workspaceResource.getProperty(CoreConstants.JCR_LASTMODIFIED, Calendar.class);
                    if (lastModified == null) {
                        lastModified = workspaceResource.getProperty(CoreConstants.JCR_CREATED, Calendar.class);
                    }
                } else if (versionReference != null) {
                    lastModified = versionReference.isActive() ? versionReference.getLastActivated() :
                            versionReference.getLastDeactivated();
                }
            }
            return lastModified;
        }

        @Nullable
        @Override
        public String getLastModifiedBy() {
            if (lastModifiedBy == null) {
                if (workspaceResource != null && workspaceResource.isValid()) {
                    lastModifiedBy = workspaceResource.getProperty(CoreConstants.JCR_LASTMODIFIED_BY, String.class);
                    if (StringUtils.isBlank(lastModifiedBy)) {
                        lastModifiedBy = workspaceResource.getProperty(CoreConstants.JCR_CREATED_BY, String.class);
                    }
                }
            }
            return lastModifiedBy;
        }

        @Nullable
        @Override
        public VersionReference getVersionReference() {
            return versionReference;
        }

        @Nullable
        @Override
        public Release getNextRelease() {
            return nextRelease;
        }

        @Nullable
        @Override
        public ReleasedVersionable getNextVersionable() {
            return nextVersionable;
        }

        @Nullable
        @Override
        public Release getPreviousRelease() {
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

    /**
     * A {@link ResourceFilter} that checks whether a resource exists in the given resolver.
     */
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

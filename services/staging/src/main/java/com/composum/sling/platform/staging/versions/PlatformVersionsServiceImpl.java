package com.composum.sling.platform.staging.versions;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.staging.*;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
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
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.*;
import static com.composum.sling.core.util.SlingResourceUtil.getPath;
import static com.composum.sling.core.util.SlingResourceUtil.getPaths;
import static com.composum.sling.platform.staging.StagingConstants.*;

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
        if (StringUtils.isBlank(releaseKey))
            return getDefaultRelease(versionable);
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
        if (handle.isValid() && handle.isOfType(TYPE_VERSIONABLE))
            return handle;
        ResourceHandle contentResource = handle.getContentResource();
        if (contentResource.isValid() && contentResource.isOfType(TYPE_VERSIONABLE))
            return contentResource;
        throw new IllegalArgumentException("Not a versionable nor something with a versionable " + CONTENT_NODE + " : " + getPath(versionable));
    }

    @Override
    @Nullable
    public StatusImpl getStatus(@Nonnull Resource rawVersionable, @Nullable String releaseKey) {
        try {
            ResourceHandle versionable = normalizeVersionable(rawVersionable);
            StagingReleaseManager.Release release = getRelease(versionable, releaseKey);
            ReleasedVersionable current = ReleasedVersionable.forBaseVersion(versionable);
            ReleasedVersionable released = releaseManager.findReleasedVersionable(release, versionable);
            return new StatusImpl(release, current, released);
        } catch (StagingReleaseManager.ReleaseRootNotFoundException | IllegalArgumentException e) {
            return null;
        }
    }

    @Nonnull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @Nonnull Resource rawVersionable, @Nullable String versionUuid) throws PersistenceException, RepositoryException {
        LOG.info("Requested activation {} in release {} to version {}", getPath(rawVersionable), releaseKey, versionUuid);
        Map<String, SiblingOrderUpdateStrategy.Result> result = null;
        ResourceHandle versionable = normalizeVersionable(rawVersionable);
        maybeCheckpoint(versionable);
        StatusImpl status = getStatus(versionable, releaseKey);
        if (status.getActivationState() != ActivationState.activated) {
            ReleasedVersionable releasedVersionable = status.currentVersionableInfo();
            if (StringUtils.isNotBlank(versionUuid) && !StringUtils.equals(versionUuid, releasedVersionable.getVersionUuid())) {
                releasedVersionable = status.releaseVersionableInfo(); // make sure we don't move the document around
                releasedVersionable.setVersionUuid(versionUuid);
            }
            releasedVersionable.setActive(true);
            result = releaseManager.updateRelease(status.release(), releasedVersionable);
            status = getStatus(versionable, releaseKey);
            Validate.isTrue(status.getVersionReference().isValid());
            Validate.isTrue(status.getActivationState() == ActivationState.activated, "Bug: not active after activation: %s", status);
            LOG.info("Activated {} in release {} to version {}", getPath(rawVersionable), status.release().getNumber(), status.currentVersionableInfo().getVersionUuid());
        } else {
            LOG.info("Already activated in release {} : {}", status.release().getNumber(), getPath(rawVersionable));
        }
        return new ActivationResult(status.release(), result, null, null, null); // FIXME(hps,2019-05-16) actresult
    }

    @Nonnull
    @Override
    public ActivationResult activate(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException {
        ActivationResult result = new ActivationResult(null, null, null, null, null); // FIXME(hps,2019-05-16) actresult
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
        if (lastModified == null) lastModified = versionable.getProperty(PROP_CREATED, Calendar.class);
        Version rootVersion = versionHistory.getRootVersion();
        if (baseVersion.isSame(rootVersion) ||
                lastModified != null && lastModified.after(baseVersion.getCreated())) {
            versionManager.checkpoint(versionable.getPath());
        }
    }

    @Override
    public void deactivate(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException {
        LOG.info("Requested deactivation {} in {}", getPaths(versionables), releaseKey);
        for (Resource versionable : versionables) {
            StatusImpl status = getStatus(versionable, releaseKey);
            switch (status.getActivationState()) {
                case modified:
                case activated:
                    LOG.info("Deactivating in {} : {}", status.release().getNumber(), getPath(versionable));
                    ReleasedVersionable releasedVersionable = status.releaseVersionableInfo();
                    releasedVersionable.setActive(false);
                    releaseManager.updateRelease(status.release(), releasedVersionable);
                    break;
                case initial:
                case deactivated:
                    LOG.info("Not deactivating in {} since not active: {}", status.release().getNumber(), getPath(versionable));
                    break;
                default:
            }
        }
    }

    @Override
    @Nonnull
    public ActivationResult revert(@Nullable String releaseKey, @Nonnull List<Resource> versionables) throws PersistenceException, RepositoryException {
        ActivationResult result = new ActivationResult(null, null, null, null, null); // FIXME(hps,2019-05-16) actresult;
        if (versionables == null || versionables.isEmpty())
            return result;
        Resource firstVersionable = versionables.get(0);
        StagingReleaseManager.Release release = getRelease(firstVersionable, releaseKey);
        StagingReleaseManager.Release previousRelease = calculatePreviousRelease(firstVersionable, release);
        if (previousRelease == null)
            throw new IllegalArgumentException("No previous release found for " + release.getNumber());

        LOG.info("Requested reverting in {} to previous release {} : {}", releaseKey, previousRelease.getNumber(), getPaths(versionables));

        for (Resource rawVersionable : versionables) {
            if (!release.appliesToPath(rawVersionable.getPath()))
                throw new IllegalArgumentException("Arguments from different releases: " + getPaths(versionables));
            ResourceHandle versionable = normalizeVersionable(rawVersionable);

            ReleasedVersionable rvInRelease = releaseManager.findReleasedVersionable(release, versionable);
            ReleasedVersionable rvInPreviousRelease = releaseManager.findReleasedVersionable(previousRelease, versionable);
            if (rvInRelease == null) {
                LOG.warn("Not reverting in {} since not present: {}", release.getNumber(), getPath(versionable));
                continue;
            }
            LOG.info("Reverting in {} from {} : {}", release, previousRelease.getNumber(), rvInPreviousRelease);

            LOG.error("This is incorrect yet, but to have something quickly...");
            // FIXME(hps,2019-05-15) implement this correctly
            if (rvInPreviousRelease != null) {
                Map<String, SiblingOrderUpdateStrategy.Result> info = releaseManager.updateRelease(release, rvInPreviousRelease);
                result = result.merge(new ActivationResult(previousRelease, info, null, null, null)); // FIXME(hps,2019-05-16) actresult
            }
        }
        return result;
    }

    @Nullable
    protected StagingReleaseManager.Release calculatePreviousRelease(Resource firstVersionable, StagingReleaseManager.Release release) {
        List<StagingReleaseManager.Release> releases = releaseManager.getReleases(firstVersionable);
        Optional<StagingReleaseManager.Release> previousRelease = releases.stream()
                .filter(r -> ReleaseNumberCreator.COMPARATOR_RELEASES.compare(r.getNumber(), release.getNumber()) < 0)
                .max(Comparator.comparing(StagingReleaseManager.Release::getNumber, ReleaseNumberCreator.COMPARATOR_RELEASES));
        return previousRelease.orElse(null);
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
            if (afterLabelledVersion && !labelledVersions.contains(version.getName()))
                continue;
            afterLabelledVersion = false;
            if (!labelledVersions.contains(version.getName()) && !versionHistory.getRootVersion().isSame(version))
                versionHistory.removeVersion(version.getName());
        }
    }

    @Nonnull
    @Override
    public ResourceFilter releaseAsResourceFilter(@Nonnull Resource resourceInRelease, @Nullable String releaseKey, @Nullable ReleaseMapper releaseMapper, @Nullable ResourceFilter additionalFilter) {
        StagingReleaseManager.Release release = getRelease(resourceInRelease, releaseKey);
        ResourceResolver resolver = releaseManager.getResolverForRelease(release, releaseMapper, false);
        return new ResolvedResourceFilter(resolver, release.toString(), additionalFilter);
    }

    protected static class StatusImpl implements Status {
        @Nonnull
        private final StagingReleaseManager.Release release;
        @Nullable
        private final ReleasedVersionable current;
        @Nullable
        private final ReleasedVersionable released;
        @Nullable // null if deleted in workspace
        private final ResourceHandle currentResource;
        @Nullable // null if not in release
        private final ResourceHandle releasedVersionReference;

        public StatusImpl(@Nonnull StagingReleaseManager.Release release, @Nullable ReleasedVersionable current, @Nullable ReleasedVersionable released) {
            this.release = Objects.requireNonNull(release);
            this.current = current;
            this.released = released;
            Resource currentResourceRaw = current != null ? release.getReleaseRoot().getChild(current.getRelativePath()) : null;
            currentResource = ResourceHandle.use(currentResourceRaw);
            if (current != null && currentResource != null && !currentResource.isValid()) // "not null but not valid" ... strange.
                throw new IllegalArgumentException("Invalid current resource " + release + " - " + current);
            releasedVersionReference =
                    current != null ?
                            ResourceHandle.use(release.getMetaDataNode().getChild("../" + NODE_RELEASE_ROOT).getChild(current.getRelativePath()))
                            : null;
        }

        @Nonnull
        @Override
        public ActivationState getActivationState() {
            if (release == null || released == null)
                return ActivationState.initial;
            if (!released.getActive())
                return ActivationState.deactivated;
            if (current == null ||
                    !StringUtils.equals(current.getVersionUuid(), released.getVersionUuid()))
                return ActivationState.modified;
            if (getLastActivated() == null ||
                    getLastModified() != null && getLastModified().after(getLastActivated()))
                return ActivationState.modified;
            return ActivationState.activated;
        }

        @Nullable
        @Override
        public Calendar getLastActivated() {
            return releasedVersionReference != null ? releasedVersionReference.getProperty(PROP_LAST_ACTIVATED, Calendar.class) : null;
        }

        @Nullable
        @Override
        public String getLastActivatedBy() {
            return releasedVersionReference != null ? releasedVersionReference.getProperty(PROP_LAST_ACTIVATED_BY, String.class) : null;
        }

        @Nullable
        @Override
        public Calendar getLastModified() {
            Calendar result = null;
            if (currentResource != null && currentResource.isValid()) {
                result = currentResource.getProperty(CoreConstants.JCR_LASTMODIFIED, Calendar.class);
                if (result == null) {
                    result = currentResource.getProperty(CoreConstants.JCR_CREATED, Calendar.class);
                }
            }
            return result;
        }

        @Nullable
        @Override
        public String getLastModifiedBy() {
            String result = null;
            if (currentResource != null && currentResource.isValid()) {
                result = currentResource.getProperty(CoreConstants.JCR_LASTMODIFIED_BY, String.class);
                if (StringUtils.isBlank(result)) {
                    result = currentResource.getProperty(CoreConstants.JCR_CREATED_BY, String.class);
                }
            }
            return result;
        }

        @Nullable
        @Override
        public Calendar getLastDeactivated() {
            return releasedVersionReference != null ? releasedVersionReference.getProperty(PROP_LAST_DEACTIVATED, Calendar.class) : null;
        }

        @Nullable
        @Override
        public String getLastDeactivatedBy() {
            return releasedVersionReference != null ? releasedVersionReference.getProperty(PROP_LAST_DEACTIVATED_BY, String.class) : null;
        }

        @Nonnull
        @Override
        public StagingReleaseManager.Release release() {
            return release;
        }

        @Nullable
        @Override
        public ReleasedVersionable releaseVersionableInfo() {
            return released;
        }

        @Nullable
        @Override
        public ReleasedVersionable currentVersionableInfo() {
            return current;
        }

        /** The version reference currently in the release. Might be invalid. */
        @Nullable
        public ResourceHandle getVersionReference() {
            return releasedVersionReference;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("release", release())
                    .append("activationState", getActivationState())
                    .append("releaseVersionableInfo", releaseVersionableInfo())
                    .append("currentVersionableInfo", currentVersionableInfo())
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

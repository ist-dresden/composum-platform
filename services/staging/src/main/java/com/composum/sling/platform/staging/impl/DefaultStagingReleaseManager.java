package com.composum.sling.platform.staging.impl;

import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventListener.ReleaseChangeEvent;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.StagingReleaseManagerPlugin;
import com.composum.sling.platform.staging.VersionReference;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy.Result;
import com.composum.sling.platform.staging.query.Query;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.JCR_UUID;
import static com.composum.sling.core.util.CoreConstants.NT_VERSION;
import static com.composum.sling.core.util.CoreConstants.PROP_MIXINTYPES;
import static com.composum.sling.core.util.CoreConstants.PROP_PRIMARY_TYPE;
import static com.composum.sling.core.util.CoreConstants.PROP_UUID;
import static com.composum.sling.core.util.CoreConstants.TYPE_CREATED;
import static com.composum.sling.core.util.CoreConstants.TYPE_LAST_MODIFIED;
import static com.composum.sling.core.util.CoreConstants.TYPE_REFERENCEABLE;
import static com.composum.sling.core.util.CoreConstants.TYPE_TITLE;
import static com.composum.sling.core.util.CoreConstants.TYPE_UNSTRUCTURED;
import static com.composum.sling.core.util.CoreConstants.TYPE_VERSIONABLE;
import static com.composum.sling.platform.staging.StagingConstants.CURRENT_RELEASE;
import static com.composum.sling.platform.staging.StagingConstants.NODE_RELEASES;
import static com.composum.sling.platform.staging.StagingConstants.NODE_RELEASE_METADATA;
import static com.composum.sling.platform.staging.StagingConstants.NODE_RELEASE_ROOT;
import static com.composum.sling.platform.staging.StagingConstants.PROP_CLOSED;
import static com.composum.sling.platform.staging.StagingConstants.PROP_DEACTIVATED;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_ACTIVATED;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_ACTIVATED_BY;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_DEACTIVATED;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_DEACTIVATED_BY;
import static com.composum.sling.platform.staging.StagingConstants.PROP_PREVIOUS_RELEASE_UUID;
import static com.composum.sling.platform.staging.StagingConstants.PROP_RELEASE_ROOT_HISTORY;
import static com.composum.sling.platform.staging.StagingConstants.PROP_VERSION;
import static com.composum.sling.platform.staging.StagingConstants.PROP_VERSIONABLEUUID;
import static com.composum.sling.platform.staging.StagingConstants.PROP_VERSIONHISTORY;
import static com.composum.sling.platform.staging.StagingConstants.RELEASE_LABEL_PREFIX;
import static com.composum.sling.platform.staging.StagingConstants.RELEASE_ROOT_PATH;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_VERSIONREFERENCE;
import static java.util.Objects.requireNonNull;
import static org.apache.jackrabbit.JcrConstants.JCR_CREATED;
import static org.apache.jackrabbit.JcrConstants.JCR_LASTMODIFIED;

/**
 * Default implementation of {@link StagingReleaseManager} - description see there.
 *
 * @see StagingReleaseManager
 */
@Component(
        service = {StagingReleaseManager.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Staging Release Manager"
        }
)
@Designate(ocd = DefaultStagingReleaseManager.Configuration.class)
public class DefaultStagingReleaseManager implements StagingReleaseManager {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStagingReleaseManager.class);

    private final SiblingOrderUpdateStrategy siblingOrderUpdateStrategy = new SiblingOrderUpdateStrategy();

    protected Configuration configuration;

    @Reference
    protected ReleaseChangeEventPublisher publisher;

    @Reference
    protected ResourceResolverFactory resolverFactory;

    protected final List<StagingReleaseManagerPlugin> plugins = Collections.synchronizedList(new ArrayList<>());

    /**
     * Random number generator for creating unique ids etc.
     */
    protected final Random random = SecureRandom.getInstanceStrong();

    public DefaultStagingReleaseManager() throws NoSuchAlgorithmException {
    }

    @Reference(
            service = StagingReleaseManagerPlugin.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addReleaseManagerPlugin(@Nonnull StagingReleaseManagerPlugin plugin) {
        LOG.info("Adding plugin {}@{}", plugin.getClass().getName(), System.identityHashCode(plugin));
        plugins.removeIf(stagingReleaseManagerPlugin -> stagingReleaseManagerPlugin == plugin);
        plugins.add(plugin);
    }

    protected void removeReleaseManagerPlugin(@Nonnull StagingReleaseManagerPlugin plugin) {
        LOG.info("Removing plugin {}@{}", plugin.getClass().getName(), System.identityHashCode(plugin));
        plugins.removeIf(stagingReleaseManagerPlugin -> stagingReleaseManagerPlugin == plugin);
    }

    @Activate
    @Deactivate
    @Modified
    public void updateConfig(Configuration configuration) {
        this.configuration = configuration;
    }

    @Nonnull
    @Override
    public ResourceHandle findReleaseRoot(@Nonnull Resource resource) throws ReleaseRootNotFoundException {
        if (resource == null) {
            throw new IllegalArgumentException("resource was null");
        }

        Resource result;
        String path = ResourceUtil.normalize(resource.getPath());
        if (SlingResourceUtil.isSameOrDescendant(RELEASE_ROOT_PATH, path)) {
            String releaseTreePath = path.substring(RELEASE_ROOT_PATH.length());
            result = new NonExistingResource(resource.getResourceResolver(), releaseTreePath);
        } else {
            result = resource;
        }

        while (result != null && !ResourceUtil.isNodeType(result, TYPE_MIX_RELEASE_ROOT)) {
            result = result.getParent();
        }
        if (result == null) {
            throw new ReleaseRootNotFoundException(SlingResourceUtil.getPath(resource));
        }
        return ResourceHandle.use(result);
    }

    @Nonnull
    @Override
    public List<Release> getReleases(@Nonnull Resource resource) {
        ResourceHandle root = findReleaseRoot(resource);
        if (root == null) {
            return Collections.emptyList();
        }
        ensureCurrentRelease(root);
        return getReleasesImpl(root);
    }

    /**
     * Finds the node below which the release data is saved - see {@link StagingConstants#RELEASE_ROOT_PATH}.
     */
    @Nonnull
    protected ResourceHandle getReleasesNode(@Nonnull Resource releaseRoot) {
        return ResourceHandle.use(releaseRoot.getResourceResolver().getResource(getReleasesNodePath(releaseRoot)));
    }

    @Nonnull
    private String getReleasesNodePath(@Nonnull Resource releaseRoot) {
        return RELEASE_ROOT_PATH + releaseRoot.getPath() + '/' + NODE_RELEASES;
    }

    @Nonnull
    protected List<Release> getReleasesImpl(@Nonnull Resource resource) {
        List<Release> result = new ArrayList<>();
        ResourceHandle root = findReleaseRoot(resource);
        if (root != null) {
            ResourceHandle releasesNode = getReleasesNode(root);
            if (releasesNode.isValid()) {
                for (Resource releaseNode : releasesNode.getChildren()) {
                    ReleaseImpl release = new ReleaseImpl(root, releaseNode);
                    result.add(release);
                }
            }
        }
        result.sort(Comparator.comparing(Release::getNumber, ReleaseNumberCreator.COMPARATOR_RELEASES));
        return result;
    }

    protected ReleaseImpl ensureCurrentRelease(@Nonnull ResourceHandle root) {
        ResourceHandle releasesNode = getReleasesNode(root);
        Resource currentReleaseNode = releasesNode.isValid() ? releasesNode.getChild(CURRENT_RELEASE) : null;
        ReleaseImpl currentRelease = null;
        if (currentReleaseNode == null) {
            // implicitly create current release which should always be there.
            try {
                createCurrentReleaseWithServiceResolver(root);
                root.getResourceResolver().refresh();
                releasesNode = getReleasesNode(root);
                currentReleaseNode = releasesNode.isValid() ? releasesNode.getChild(CURRENT_RELEASE) : null;
                if (currentReleaseNode != null) {
                    currentRelease = new ReleaseImpl(root, currentReleaseNode);
                } else {
                    LOG.warn("Release node not accessible for {} : {}", root.getResourceResolver().getUserID(),
                            SlingResourceUtil.getPath(root));
                }
            } catch (RepositoryException | PersistenceException | LoginException ex) {
                LOG.error("Trouble creating current release for " + root.getPath(), ex);
            }
        } else {
            currentRelease = new ReleaseImpl(root, currentReleaseNode);
        }
        return currentRelease;
    }

    /**
     * Create the current release using a service resolver since this can be a user who hasn't the rights,
     * and this can be called during a read operation where not having the rights is perfectly OK.
     * If there are other releases, we copy it from the highest numbered release. (That happens if you delete the
     * current release to reset it).
     */
    protected void createCurrentReleaseWithServiceResolver(@Nonnull ResourceHandle currentUserRoot) throws LoginException,
            PersistenceException, RepositoryException {
        try (ResourceResolver serviceResolver = resolverFactory.getServiceResourceResolver(null)) {
            Resource root = serviceResolver.getResource(currentUserRoot.getPath());
            if (root == null) {
                LOG.warn("Service resolver can't access {}", currentUserRoot.getPath());
                return;
            }
            ReleaseImpl currentRelease;
            Optional<Release> highestNumericRelease = getReleasesImpl(root).stream()
                    .filter(r -> !CURRENT_RELEASE.equals(r.getNumber()))
                    .max(Comparator.comparing(Release::getNumber, ReleaseNumberCreator.COMPARATOR_RELEASES));
            if (highestNumericRelease.isPresent()) {
                currentRelease = createReleaseImpl(ResourceHandle.use(root),
                        ReleaseImpl.unwrap(highestNumericRelease.get()), CURRENT_RELEASE);
                setPreviousRelease(currentRelease, highestNumericRelease.get());
                serviceResolver.delete(currentRelease.getMetaDataNode()); // clear metadata and recreate the node
            }
            currentRelease = ensureRelease(root, CURRENT_RELEASE);
            serviceResolver.commit();
            LOG.info("Created current release for {} with {}", currentUserRoot.getPath(), serviceResolver.getUserID());
        }
    }

    @Nonnull
    @Override
    public Release findRelease(@Nonnull Resource resource, @Nonnull String releaseNumber) {
        ResourceHandle root = findReleaseRoot(resource);
        if (releaseNumber.equals(CURRENT_RELEASE)) {
            ReleaseImpl release = ensureCurrentRelease(root);
            if (release == null) // weird trouble creating it
            {
                throw new ReleaseNotFoundException("Unexpected trouble creating or reading release.");
            }
            return release;
        }
        return findReleaseImpl(root, releaseNumber);
    }

    /**
     * Implementation that does not autocreate the current release, as {@link #findRelease(Resource, String)} does.
     */
    @Nonnull
    protected Release findReleaseImpl(@Nonnull Resource resource, @Nonnull String releaseNumber) {
        ResourceHandle root = findReleaseRoot(resource);
        ResourceHandle releaseNode = getReleasesNode(root);
        releaseNode = releaseNode.isValid() ? ResourceHandle.use(releaseNode.getChild(releaseNumber)) : null;
        if (releaseNode == null || !releaseNode.isValid()) {
            throw new ReleaseNotFoundException();
        }
        return new ReleaseImpl(root, releaseNode);
    }

    @Nonnull
    @Override
    public Release findReleaseByUuid(@Nonnull Resource resource, @Nonnull String releaseUuid) throws ReleaseNotFoundException {
        for (Release release : getReleasesImpl(resource)) {
            if (release.getUuid().equals(releaseUuid)) {
                return release;
            }
        }
        throw new ReleaseNotFoundException();
    }

    @Nonnull
    @Override
    @Deprecated
    public Release createRelease(@Nonnull Release copyFromRelease, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, PersistenceException, RepositoryException {
        if (CURRENT_RELEASE.equals(copyFromRelease.getNumber())) {
            throw new IllegalArgumentException("Cannot create release from current release.");
        }
        return createRelease(copyFromRelease.getReleaseRoot(), copyFromRelease, releaseType);
    }

    @Nonnull
    @Override
    public Release resetCurrentTo(@Nonnull Release release) throws PersistenceException, RepositoryException, ReleaseProtectedException {
        LOG.info("Resetting current to {}", release);
        deleteRelease(this.findReleaseImpl(release.getReleaseRoot(), CURRENT_RELEASE));
        try {
            return createRelease(release, givenReleaseNumber(CURRENT_RELEASE));
        } catch (ReleaseExistsException e) { // impossible since current was deleted.
            throw new RepositoryException("Bug.", e);
        }
    }

    @Nonnull
    @Override
    public Map<String, String> nextRealeaseNumbers(@Nonnull Resource resource) {
        ResourceHandle root = findReleaseRoot(resource);
        ReleaseImpl currentRelease = ReleaseImpl.unwrap(findRelease(root, StagingConstants.CURRENT_RELEASE));
        try {
            Release previousRelease = currentRelease.getPreviousRelease();
            if (previousRelease != null) {
                String lastNumber = previousRelease.getNumber();
                return new LinkedHashMap<>() {{
                    put(ReleaseNumberCreator.MAJOR.name(), ReleaseNumberCreator.MAJOR.bumpRelease(lastNumber).substring(1));
                    put(ReleaseNumberCreator.MINOR.name(), ReleaseNumberCreator.MINOR.bumpRelease(lastNumber).substring(1));
                    put(ReleaseNumberCreator.BUGFIX.name(), ReleaseNumberCreator.BUGFIX.bumpRelease(lastNumber).substring(1));
                }};
            }
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return new LinkedHashMap<>() {{ // defaults for the first release
            put(ReleaseNumberCreator.MAJOR.name(), "1");
            put(ReleaseNumberCreator.MINOR.name(), "0.1");
            put(ReleaseNumberCreator.BUGFIX.name(), "0.0.1");
        }};
    }

    @Nonnull
    @Override
    public Release finalizeCurrentRelease(@Nonnull Resource resource, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, RepositoryException, PersistenceException {
        ResourceHandle root = findReleaseRoot(resource);
        ReleaseImpl currentRelease = ReleaseImpl.unwrap(findRelease(root, StagingConstants.CURRENT_RELEASE));
        String newReleaseNumber = currentRelease.getPreviousRelease() != null ?
                releaseType.bumpRelease(currentRelease.getPreviousRelease().getNumber()) :
                releaseType.bumpRelease("");

        try {
            findReleaseImpl(root, newReleaseNumber);
            throw new ReleaseExistsException(root, newReleaseNumber);
        } catch (ReleaseNotFoundException e) {
            // expected
        }

        Session session = root.getResourceResolver().adaptTo(Session.class);
        session.move(currentRelease.getReleaseNode().getPath(), currentRelease.getReleaseNode().getParent().getPath() + "/" + newReleaseNumber);

        ReleaseImpl newRelease = ReleaseImpl.unwrap(findReleaseImpl(root, newReleaseNumber));
        updateReleaseLabels(newRelease);
        closeRelease(newRelease);

        createReleaseImpl(root, newRelease, CURRENT_RELEASE);
        LOG.info("Finalizing current release to {}", newRelease);
        return newRelease;
    }

    @Nonnull
    protected Release createRelease(@Nonnull Resource resource, @Nullable Release rawCopyFromRelease, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, ReleaseNotFoundException, PersistenceException, RepositoryException {
        ResourceHandle root = findReleaseRoot(resource);
        ReleaseImpl copyFromRelease = rawCopyFromRelease != null ? ReleaseImpl.unwrap(rawCopyFromRelease) : ensureCurrentRelease(root);

        String newReleaseNumber = releaseType.bumpRelease(copyFromRelease.getNumber());

        try {
            findReleaseImpl(root, newReleaseNumber);
            throw new ReleaseExistsException(root, newReleaseNumber);
        } catch (ReleaseNotFoundException e) {
            // expected
        }

        return createReleaseImpl(root, copyFromRelease, newReleaseNumber);
    }

    @Nonnull
    protected ReleaseImpl createReleaseImpl(ResourceHandle root, ReleaseImpl copyFromRelease, String newReleaseNumber) throws RepositoryException, PersistenceException {
        cleanupLabels(root);
        ReleaseImpl newRelease = ensureRelease(root, newReleaseNumber);
        if (null != copyFromRelease) {
            new NodeTreeSynchronizer().update(copyFromRelease.getReleaseNode(), newRelease.getReleaseNode());
        }
        setPreviousRelease(newRelease, copyFromRelease);
        ResourceHandle.use(newRelease.getReleaseNode()).setProperty(PROP_CLOSED, (String) null);

        updateReleaseLabels(newRelease);
        return newRelease;
    }

    protected void setPreviousRelease(Release rawNewRelease, Release rawPreviousRelease) throws RepositoryException {
        ReleaseImpl previousRelease = ReleaseImpl.unwrap(rawPreviousRelease);
        ReleaseImpl newRelease = ReleaseImpl.unwrap(rawNewRelease);
        String prevUuid = previousRelease != null ? previousRelease.getReleaseNode().getValueMap().get(JCR_UUID, String.class) : null;
        ResourceHandle.use(newRelease.getReleaseNode()).setProperty(PROP_PREVIOUS_RELEASE_UUID,
                prevUuid, PropertyType.REFERENCE);
    }

    @Nonnull
    @Override
    public List<ReleasedVersionable> listReleaseContents(@Nonnull Release rawRelease) {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        List<ReleasedVersionable> result = new ArrayList<>();
        Resource releaseWorkspaceCopy = requireNonNull(release.getReleaseNode().getChild(NODE_RELEASE_ROOT));
        Query query = release.getReleaseRoot()
                .getResourceResolver()
                .adaptTo(QueryBuilder.class)
                .createQuery();
        query.path(releaseWorkspaceCopy.getPath()).type(TYPE_VERSIONREFERENCE);
        for (Resource versionReference : query.execute()) {
            result.add(ReleasedVersionable.fromVersionReference(releaseWorkspaceCopy, versionReference));
        }
        return result;
    }

    @Nonnull
    @Override
    public List<ReleasedVersionable> compareReleases(@Nonnull Release release, @Nullable Release previousRelease) throws RepositoryException {
        List<ReleasedVersionable> result = new ArrayList<>();
        if (previousRelease == null) {
            previousRelease = release.getPreviousRelease();
        }

        if (previousRelease == null) // return everything
        {
            return listReleaseContents(release);
        }

        List<ReleasedVersionable> releaseContents = listReleaseContents(release);
        Map<String, ReleasedVersionable> releaseByVersionHistory = releaseContents.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        List<ReleasedVersionable> previousContents = listReleaseContents(previousRelease);
        Map<String, ReleasedVersionable> previousByVersionHistory = previousContents.stream()
                .collect(Collectors.toMap(ReleasedVersionable::getVersionHistory, Function.identity()));

        for (ReleasedVersionable releasedVersionable : releaseContents) {
            if (!releasedVersionable.equals(previousByVersionHistory.get(releasedVersionable.getVersionHistory()))) {
                result.add(releasedVersionable);
            }
        }

        for (ReleasedVersionable previousVersionable : previousContents) {
            if (!releaseByVersionHistory.containsKey(previousVersionable.getVersionHistory())) {
                previousVersionable.setVersionUuid(null);
                result.add(previousVersionable);
            }
        }
        return result;
    }

    @Nonnull
    @Override
    public List<ReleasedVersionable> listWorkspaceContents(@Nonnull Resource resource) {
        ResourceHandle root = findReleaseRoot(resource);
        ensureCurrentRelease(ResourceHandle.use(root));
        Query query = root.getResourceResolver()
                .adaptTo(QueryBuilder.class)
                .createQuery();
        query.path(root.getPath()).type(TYPE_VERSIONABLE);

        List<ReleasedVersionable> result = new ArrayList<>();
        for (Resource versionable : query.execute()) {
            result.add(ReleasedVersionable.forBaseVersion(versionable));
        }
        return result;
    }

    @Nullable
    @Override
    public ReleasedVersionable findReleasedVersionableByUuid(@Nonnull Release rawRelease, @Nonnull String versionHistoryUuid) {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        Resource releaseWorkspaceCopy = release.getWorkspaceCopyNode();

        Query query = releaseWorkspaceCopy.getResourceResolver()
                .adaptTo(QueryBuilder.class)
                .createQuery();
        query.path(releaseWorkspaceCopy.getPath()).type(TYPE_VERSIONREFERENCE).condition(
                query.conditionBuilder().property(PROP_VERSIONHISTORY).eq().val(versionHistoryUuid)
        );

        Iterator<Resource> versionReferences = query.execute().iterator();
        Resource versionReference = versionReferences.hasNext() ? versionReferences.next() : null;
        return versionReference != null ? ReleasedVersionable.fromVersionReference(releaseWorkspaceCopy, versionReference) : null;
    }

    @Nullable
    @Override
    public ReleasedVersionable findReleasedVersionable(@Nonnull Release rawRelease, @Nonnull Resource versionable) {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        String expectedPath = release.mapToContentCopy(versionable.getPath());
        Resource versionReference = versionable.getResourceResolver().getResource(expectedPath);
        if (ResourceUtil.isNonExistingResource(versionable)) {
            if (versionReference != null) {
                return ReleasedVersionable.fromVersionReference(release.getWorkspaceCopyNode(), versionReference);
            }
            return null;
        }
        ReleasedVersionable currentVersionable = ReleasedVersionable.forBaseVersion(versionable);
        if (versionReference != null) { // if it's at the expected path
            ReleasedVersionable releasedVersionable = ReleasedVersionable.fromVersionReference(release.getWorkspaceCopyNode(), versionReference);
            if (StringUtils.equals(releasedVersionable.getVersionHistory(), currentVersionable.getVersionHistory())) {
                return releasedVersionable;
            }
        }
        // otherwise we have to search (was moved or isn't present at all).
        return findReleasedVersionableByUuid(release, currentVersionable.getVersionHistory());
    }

    @Nullable
    @Override
    public ReleasedVersionable findReleasedVersionable(@Nonnull Release rawRelease, @Nonnull String path) {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        String abspath = release.absolutePath(path);
        return findReleasedVersionable(release,
                new NonExistingResource(release.getReleaseRoot().getResourceResolver(), abspath));
    }

    @Nonnull
    @Override
    public Map<String, Result> updateRelease(@Nonnull Release release, @Nonnull List<ReleasedVersionable> releasedVersionableList) throws RepositoryException, PersistenceException, ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        ReleaseChangeEvent event = new ReleaseChangeEvent(release);
        Map<String, Result> result = new TreeMap<>();
        for (ReleasedVersionable releasedVersionable : releasedVersionableList) {
            Map<String, Result> partialResult = updateReleaseInternal(release, releasedVersionable, event);
            result = Result.combine(result, partialResult);
        }
        applyPlugins(release, releasedVersionableList, event);
        publisher.publishActivation(event);
        return result;
    }

    @Nonnull
    @Override
    public Map<String, Result> revert(@Nonnull Release release, @Nonnull String pathToRevert,
                                      @Nullable Release rawFromRelease) throws RepositoryException, PersistenceException
            , ReleaseClosedException, ReleaseChangeEventListener.ReplicationFailedException {
        Map<String, Result> result;
        ReleasedVersionable versionableInCurrentRelease = findReleasedVersionable(release, pathToRevert);

        if (rawFromRelease == null) { // special case: remove from release, e.g. if accidentially introduced.
            if (versionableInCurrentRelease != null) {
                versionableInCurrentRelease.setVersionUuid(null);
                return updateRelease(release, Collections.singletonList(versionableInCurrentRelease));
            } else {
                return Collections.emptyMap();
            }
        }

        ReleaseImpl fromRelease = requireNonNull(ReleaseImpl.unwrap(rawFromRelease));
        pathToRevert = fromRelease.absolutePath(pathToRevert);
        ReleaseChangeEvent event = new ReleaseChangeEvent(release);
        ReleasedVersionable versionableInPreviousRelease = findReleasedVersionable(fromRelease, pathToRevert);
        if (versionableInPreviousRelease == null) { // remove whatever is there at that path, if there is anything
            if (versionableInCurrentRelease == null || !versionableInCurrentRelease.isActive()) {
                return new HashMap<>();
            }
            versionableInCurrentRelease.setActive(false);
            return updateRelease(release, Collections.singletonList(versionableInCurrentRelease));
        } else {
            // like updateRelease, but update parents from the previous release instead of the workspace,
            // and only if there originally was no parent.
            Resource versionReferenceResource = fromRelease.getWorkspaceCopyNode().getChild(versionableInPreviousRelease.getRelativePath());
            result = new ReleaseUpdater(release, versionableInPreviousRelease, event, versionReferenceResource).callForRevert();

            applyPlugins(release, Collections.singletonList(versionableInPreviousRelease), event);
        }
        publisher.publishActivation(event);
        return result;
    }

    @Nonnull
    protected Map<String, Result> updateReleaseInternal(@Nonnull Release rawRelease, @Nonnull ReleasedVersionable releasedVersionable, ReleaseChangeEvent event) throws RepositoryException, PersistenceException, ReleaseClosedException {
        return new ReleaseUpdater(rawRelease, releasedVersionable, event).callForUpdate();
    }

    /**
     * Method object that performs the heavy lifting for update or revert. We use a method object to be able to
     * structure things better without passing around gazillions of (possibly even return-)parameters.
     */
    protected class ReleaseUpdater {
        @Nonnull
        protected final ReleaseImpl release;
        @Nonnull
        protected final ReleasedVersionable releasedVersionable;
        @Nonnull
        protected final ReleaseChangeEvent event;
        protected final boolean delete;
        @Nonnull
        protected final ResourceResolver resolver;
        protected Resource copiedVersionReferenceResource = null;
        protected final NodeTreeSynchronizer sync =
                new NodeTreeSynchronizer().addIgnoredAttributes(StagingConstants.PROP_CHANGE_NUMBER);

        protected Resource versionReference;
        protected final Resource releaseWorkspaceCopy;
        protected final String newPath;
        protected ReleasedVersionable previousRV;
        protected final Map<String, Result> result = new HashMap<>();

        public ReleaseUpdater(@Nonnull Release rawRelease, @Nonnull ReleasedVersionable releasedVersionable,
                              @Nonnull ReleaseChangeEvent event) throws ReleaseClosedException,
                RepositoryException {
            this.releasedVersionable = releasedVersionable;
            delete = releasedVersionable.getVersionUuid() == null;
            this.event = event;

            release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
            if (release.isClosed()) {
                throw new ReleaseClosedException();
            }
            validateForUpdate(releasedVersionable, release);
            releaseWorkspaceCopy = release.getWorkspaceCopyNode();
            newPath = releaseWorkspaceCopy.getPath() + '/' + releasedVersionable.getRelativePath();
            resolver = release.getReleaseRoot().getResourceResolver();

            determineCurrentUseInRelease();
        }

        public ReleaseUpdater(@Nonnull Release release, @Nonnull ReleasedVersionable releasedVersionable,
                              @Nonnull ReleaseChangeEvent event,
                              @Nullable Resource copiedVersionReferenceResource) throws ReleaseClosedException,
                RepositoryException {
            this(release, releasedVersionable, event);
            this.copiedVersionReferenceResource = copiedVersionReferenceResource;
        }

        /**
         * Finds out whether the releasedVersionable is already in the release-> versionReference, previousRV .
         */
        protected void determineCurrentUseInRelease() {
            versionReference = releaseWorkspaceCopy.getResourceResolver().getResource(newPath);
            previousRV = versionReference != null ? ReleasedVersionable.fromVersionReference(releaseWorkspaceCopy, versionReference) : null;
            if (versionReference == null || !StringUtils.equals(previousRV.getVersionHistory(), releasedVersionable.getVersionHistory())) {
                // check whether it was moved. Caution: queries work only for comitted content
                Query query = releaseWorkspaceCopy.getResourceResolver()
                        .adaptTo(QueryBuilder.class)
                        .createQuery();
                query.path(releaseWorkspaceCopy.getPath()).type(TYPE_VERSIONREFERENCE).condition(
                        query.conditionBuilder().property(PROP_VERSIONABLEUUID).eq().val(releasedVersionable.getVersionableUuid())
                );

                Iterator<Resource> versionReferences = query.execute().iterator();
                versionReference = versionReferences.hasNext() ? versionReferences.next() : null;
                previousRV = versionReference != null ? ReleasedVersionable.fromVersionReference(releaseWorkspaceCopy, versionReference) : null;
            }
        }

        public Map<String, Result> callForUpdate() throws RepositoryException, PersistenceException {
            moveOrCreateVersionReference();

            if (!delete) {
                releasedVersionable.writeToVersionReference(releaseWorkspaceCopy, requireNonNull(versionReference));
                adjustParentsDeletedFlags();
            }

            bumpReleaseChangeNumber(release);
            release.updateLastModified();
            updateReleaseLabel();
            updateEvent();

            updateParentsAndCreateResult();
            return result;
        }

        public Map<String, Result> callForRevert() throws RepositoryException, PersistenceException {
            moveOrCreateVersionReference();

            if (!delete) {
                releasedVersionable.writeToVersionReference(releaseWorkspaceCopy, requireNonNull(versionReference));
                adjustParentsDeletedFlags();
            }

            bumpReleaseChangeNumber(release);
            release.updateLastModified();
            updateReleaseLabel();
            updateEvent();

            // do not update parents from workspace - we only update parents we create
            return result;
        }


        /**
         * We create, move or delete the versionReference, as appropriate for our operation.
         */
        protected void moveOrCreateVersionReference() throws RepositoryException, PersistenceException {
            if (delete) {
                deleteVersionReference();
            } else { // !delete
                if (versionReference == null) {
                    createMissingParents();
                    versionReference = ResourceUtil.getOrCreateResource(release.getReleaseNode().getResourceResolver(), newPath,
                            TYPE_UNSTRUCTURED + '/' + TYPE_VERSIONREFERENCE);
                } else if (!versionReference.getPath().equals(newPath)) { // move to a different path
                    Resource oldParent = versionReference.getParent();
                    createMissingParents();
                    checkAndRemoveOldReferenceForMove(newPath);
                    resolver.adaptTo(Session.class).move(versionReference.getPath(), newPath);
                    versionReference = resolver.getResource(newPath);

                    cleanupOrphans(releaseWorkspaceCopy.getPath(), oldParent);
                } else { // stays at same path
                    String existingVersionHistory = versionReference.getValueMap().get(PROP_VERSIONHISTORY, String.class);
                    if (!StringUtils.equals(existingVersionHistory, releasedVersionable.getVersionHistory())) {
                        LOG.warn("Overriding a different versionable {} at the requested path {}", existingVersionHistory,
                                releasedVersionable.getRelativePath());
                    }
                }
            }
        }

        /**
         * Deletes the version reference and now obsolete parent nodes without any child nodes left.
         */
        protected void deleteVersionReference() throws PersistenceException {
            if (versionReference != null) {
                Resource parent = versionReference.getParent();
                event.addMoveOrUpdate(versionReference.getPath(), null);
                resolver.delete(versionReference);

                cleanupOrphans(releaseWorkspaceCopy.getPath(), parent);
            }
        }

        /**
         * Removes old nodes that are not version references and have no children. That can happen when a versionreference
         * is moved to another node and there is no version reference left below it's old parent.
         */
        protected void cleanupOrphans(String releaseWorkspaceCopyPath, Resource parent) throws PersistenceException {
            boolean inRelease = false;
            while (parent != null
                    && (inRelease = StringUtils.startsWith(parent.getPath(), releaseWorkspaceCopyPath + "/"))
                    && !parent.hasChildren()
            ) {
                Resource todelete = parent;
                parent = parent.getParent();
                LOG.info("Deleting obsolete {}", todelete.getPath());
                event.addMoveOrUpdate(todelete.getPath(), null);
                resolver.delete(todelete);
            }
            if (inRelease) { // parent is a node that has children
                maybeSetDeletedFlag(parent);
            }
        }

        /**
         * When moving, we need to check whether there already is a version reference. This one has to be deleted,
         * otherwise the move will fail. A warning is logged, but we assume the user knows what he is doing; it can
         * will show up in the version differences and can be reverted, anyway.
         */
        protected void checkAndRemoveOldReferenceForMove(String newPath) throws PersistenceException {
            Resource resourceAtPath = resolver.getResource(newPath);
            if (resourceAtPath != null) {
                if (!ResourceUtil.isPrimaryType(resourceAtPath, TYPE_VERSIONREFERENCE)) {
                    throw new IllegalArgumentException("Trying to replace a non-versionreference with a " +
                            "versionreference - something is fishy, here: " + newPath);
                }
                VersionReferenceImpl oldVersionReference = new VersionReferenceImpl(release, resourceAtPath);
                ReleasedVersionable releasedVersionable = oldVersionReference.getReleasedVersionable();
                resolver.delete(resourceAtPath);
                LOG.warn("Removing VersionReference that is going to be overwritten: {} : {}", newPath, releasedVersionable);
            }
        }

        protected void adjustParentsDeletedFlags() {
            if (releasedVersionable.isActive()) {
                resetDeletedFlag(versionReference.getParent());
            } else {
                maybeSetDeletedFlag(versionReference.getParent());
            }
        }

        /**
         * If a versionreference is active, we need to make sure all parents are active since it won't be visible otherwise.
         */
        protected void resetDeletedFlag(Resource resource) {
            if (resource != null && SlingResourceUtil.isSameOrDescendant(release.getWorkspaceCopyNode().getPath(),
                    resource.getPath())) {
                if (resource.getValueMap().get(PROP_DEACTIVATED, false)) {
                    resource.adaptTo(ModifiableValueMap.class).remove(PROP_DEACTIVATED);
                    event.addMoveOrUpdate(null, resource.getPath());
                }
                resetDeletedFlag(resource.getParent());
            }
        }

        /**
         * Check that all parents have either an active versionreference below or are marked as deactivated.
         */
        protected void maybeSetDeletedFlag(Resource resource) {
            if (resource != null && SlingResourceUtil.isSameOrDescendant(release.getWorkspaceCopyNode().getPath(),
                    resource.getPath())) {
                if (resource.getValueMap().get(PROP_DEACTIVATED, false)) {
                    return;
                }
                if (!hasActiveVersionReferenceDescendant(resource)) {
                    resource.adaptTo(ModifiableValueMap.class).put(PROP_DEACTIVATED, true);
                    event.addMoveOrUpdate(resource.getPath(), null);
                    maybeSetDeletedFlag(resource.getParent());
                }
            }
        }

        protected boolean hasActiveVersionReferenceDescendant(Resource parent) {
            if (parent.getValueMap().get(PROP_DEACTIVATED, false)) {
                return false;
            }
            if (VersionReferenceImpl.isVersionReference(parent)) {
                return true;
            }
            for (Resource child : parent.getChildren()) {
                if (hasActiveVersionReferenceDescendant(child)) {
                    return true;
                }
            }
            return false;
        }

        protected void createMissingParents() throws RepositoryException, PersistenceException {
            if (copiedVersionReferenceResource == null) {
                createParentNodesWithoutTemplate(ResourceUtil.getParent(newPath));
            } else {
                copyParentIfMissing(ResourceUtil.getParent(newPath), copiedVersionReferenceResource.getParent());
            }
        }

        @Nonnull
        protected Resource createParentNodesWithoutTemplate(String path) throws PersistenceException {
            Resource nodeResource = resolver.getResource(path);
            if (null == nodeResource) {
                Resource parent = createParentNodesWithoutTemplate(ResourceUtil.getParent(path));
                Map<String, Object> props = ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                        ResourceUtil.JCR_FROZENPRIMARYTYPE, TYPE_UNSTRUCTURED);
                nodeResource = resolver.create(parent, ResourceUtil.getName(path), props);
                event.addMoveOrUpdate(null, path);
            }
            return nodeResource;
        }

        /**
         * Create all levels of parents; on the first created parent update the sibling order.
         *
         * @return the possibly created node with path nodeToCreate
         */
        @Nonnull
        protected Resource copyParentIfMissing(String nodeToCreate, Resource nodeTemplate) throws RepositoryException {
            String parentPath = ResourceUtil.getParent(nodeToCreate);
            Resource parent = resolver.getResource(parentPath);
            boolean updateSiblingOrderOnCreate = false;
            if (parent == null) {
                parent = copyParentIfMissing(parentPath, nodeTemplate.getParent());
            } else {
                updateSiblingOrderOnCreate = true;
            }
            String nodeName = ResourceUtil.getName(nodeToCreate);
            Resource node = parent.getChild(nodeName);
            if (node == null) {
                node = ResourceUtil.getOrCreateChild(parent, nodeName, TYPE_UNSTRUCTURED);
                sync.updateAttributes(ResourceHandle.use(nodeTemplate), ResourceHandle.use(node), StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES);
                if (updateSiblingOrderOnCreate) {
                    updateSiblingOrderAndSaveResult(ResourceHandle.use(nodeTemplate), ResourceHandle.use(node));
                }
                event.addMoveOrUpdate(null, nodeToCreate);
            }
            return node;
        }

        protected void updateParentsAndCreateResult() throws RepositoryException {
            if (!delete && releasedVersionable.isActive()) {
                updateParentsFromWorkspace();
            }
        }

        /**
         * Goes through all parents of the version reference, sets their attributes from the working copy
         * and fixes the node ordering if necessary.
         */
        protected void updateParentsFromWorkspace() throws RepositoryException {
            ResourceHandle template = ResourceHandle.use(release.getReleaseRoot());
            ResourceHandle inRelease = ResourceHandle.use(release.getWorkspaceCopyNode());
            String[] levels = releasedVersionable.getRelativePath().split("/");
            Iterator<String> levelIterator = IteratorUtils.arrayIterator(levels);

            while (template.isValid() && inRelease.isValid() && !inRelease.isOfType(TYPE_VERSIONREFERENCE)) {
                boolean attributesChanged = sync.updateAttributes(template, inRelease, StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES);
                if (attributesChanged) {
                    event.addMoveOrUpdate(template.getPath(), template.getPath());
                }
                if (!levelIterator.hasNext()) {
                    break;
                }
                String level = levelIterator.next();
                template = ResourceHandle.use(template.getChild(level));
                inRelease = ResourceHandle.use(inRelease.getChild(level));
                if (template.isValid() && inRelease.isValid()) {
                    // we do that for all nodes except the root but including the version reference itself:
                    updateSiblingOrderAndSaveResult(template, inRelease);
                }
            }
            if (levelIterator.hasNext() && !template.isValid()) {
                throw new IllegalArgumentException("Could not copy attributes of parent nodes since node used as template not valid: " + inRelease.getPath());
            }
        }

        protected void updateSiblingOrderAndSaveResult(ResourceHandle from, ResourceHandle to) throws RepositoryException {
            Result siblingResult = updateSiblingOrder(from, to);
            if (siblingResult != Result.unchanged) {
                String releasePath = release.unmapFromContentCopy(to.getPath());
                String parent = ResourceUtil.getParent(releasePath);
                result.put(parent, siblingResult);
                event.addMoveOrUpdate(parent, parent);
            }
        }

        protected void updateEvent() {
            boolean wasThere = previousRV != null && previousRV.isActive() && previousRV.getVersionUuid() != null;
            String wasPath = wasThere ? release.absolutePath(previousRV.getRelativePath()) : null;

            boolean isThere = releasedVersionable.isActive() && releasedVersionable.getVersionUuid() != null;
            String isPath = isThere ? release.absolutePath(releasedVersionable.getRelativePath()) : null;

            if (wasThere || isThere) {
                event.addMoveOrUpdate(wasPath, isPath);
            }
        }

        /**
         * Sets the label {@link StagingConstants#RELEASE_LABEL_PREFIX}-{releasenumber} on the version the releasedVersionable refers to.
         */
        protected void updateReleaseLabel() throws RepositoryException {
            Resource root = release.getReleaseRoot();
            Session session = root.getResourceResolver().adaptTo(Session.class);
            if (session == null) {
                throw new RepositoryException("No session for " + root.getPath()); // impossible
            }
            VersionManager versionManager = session.getWorkspace().getVersionManager();

            VersionHistory versionHistory = null;
            try {
                versionHistory = versionManager.getVersionHistory(release.getReleaseRoot().getPath() + '/' + releasedVersionable.getRelativePath());
                if (!versionHistory.getIdentifier().equals(releasedVersionable.getVersionHistory())) {
                    versionHistory = null;
                }
            } catch (PathNotFoundException e) {
                // moved or deleted. Try versionhistoryuuid
            }
            if (versionHistory == null) {
                versionHistory = (VersionHistory) session.getNodeByIdentifier(releasedVersionable.getVersionHistory());
            }
            if (versionHistory == null) {
                LOG.debug("No version history anymore for {} : {}", release, releasedVersionable);
                return;
            }
            String label = release.getReleaseLabel();

            if (StringUtils.isBlank(releasedVersionable.getVersionUuid())) {
                try {
                    versionHistory.removeVersionLabel(label);
                } catch (VersionException e) {
                    LOG.debug("Label {} wasn't set - OK.", label);
                }
                return;
            }

            for (Version version : JcrIteratorUtil.asIterable(versionHistory.getAllVersions())) {
                if (releasedVersionable.getVersionUuid().equals(version.getIdentifier())) {
                    versionHistory.addVersionLabel(version.getName(), label, true);
                    LOG.debug("Setting label {} on version {}", label, version.getIdentifier());
                    return;
                }
            }

            throw new IllegalArgumentException("Version not found for " + releasedVersionable + " in release " + release);
        }
    }


    protected void applyPlugins(Release rawRelease, List<ReleasedVersionable> releasedVersionableList, ReleaseChangeEvent event) throws RepositoryException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        ArrayList<StagingReleaseManagerPlugin> pluginscopy = new ArrayList<>(plugins); // avoid concurrent modifiation problems
        Set<String> changedPaths = new HashSet<>();
        for (ReleasedVersionable releasedVersionable : releasedVersionableList) {
            changedPaths.add(release.mapToContentCopy(releasedVersionable.getRelativePath()));
        }
        for (StagingReleaseManagerPlugin plugin : pluginscopy) {
            plugin.fixupReleaseForChanges(release, release.getWorkspaceCopyNode(), changedPaths, event);
        }
    }

    /**
     * We check that the mandatory fields are set and that it isn't the root version.
     */
    protected void validateForUpdate(ReleasedVersionable releasedVersionable, ReleaseImpl release) throws RepositoryException {
        Validate.notNull(releasedVersionable.getVersionHistory(), "No versionhistory set for %s/%s", release, releasedVersionable.getRelativePath());
        Validate.notBlank(releasedVersionable.getRelativePath(), "No relative path set for %s %s", release, releasedVersionable);
        if (StringUtils.isNotBlank(releasedVersionable.getVersionUuid())) {
            Resource version = ResourceUtil.getByUuid(release.getReleaseRoot().getResourceResolver(), releasedVersionable.getVersionUuid());
            Validate.isTrue(ResourceUtil.isPrimaryType(version, NT_VERSION), "Not a version: ", SlingResourceUtil.getPath(version));
            Validate.isTrue(!"jcr:rootVersion".equals(version.getName()), "Versionable was never checked in: %s/%s", release, releasedVersionable.getRelativePath());
            Validate.isTrue(StringUtils.equals(releasedVersionable.getVersionHistory(), version.getChild("..").getValueMap().get(JCR_UUID, String.class)),
                    "Version history and version do not match for %s %s", release, releasedVersionable);
        }
    }

    /**
     * Sets the label on the versionables contained in all versionables in the release
     */
    protected void updateReleaseLabels(@Nonnull ReleaseImpl release) throws RepositoryException {
        for (Resource releaseContent : SlingResourceUtil.descendants(release.getWorkspaceCopyNode())) {
            if (ResourceUtil.isNodeType(releaseContent, TYPE_VERSIONREFERENCE)) {
                String versionUuid = releaseContent.getValueMap().get(PROP_VERSION, String.class);
                Resource version = ResourceUtil.getByUuid(releaseContent.getResourceResolver(), versionUuid);
                VersionHistory versionHistory = (VersionHistory) version.getParent().adaptTo(Node.class);
                versionHistory.addVersionLabel(version.getName(), release.getReleaseLabel(), true);
            }
        }
    }

    /**
     * Adjusts the place of {to} wrt. it's siblings to be consistent with {from}.
     *
     * @return true if the ordering was deterministic, false if there was heuristics involved and the user should check the result.
     */
    protected Result updateSiblingOrder(ResourceHandle from, ResourceHandle to) throws RepositoryException {
        return getSiblingOrderUpdateStrategy().adjustSiblingOrderOfDestination(from, to);
    }

    protected SiblingOrderUpdateStrategy getSiblingOrderUpdateStrategy() {
        return siblingOrderUpdateStrategy;
    }

    @Override
    @Nonnull
    public ResourceResolver getResolverForRelease(@Nonnull Release release, @Nullable ReleaseMapper releaseMapper, boolean closeResolverOnClose) {
        return new StagingResourceResolver(release, ReleaseImpl.unwrap(release).getReleaseRoot().getResourceResolver(),
                releaseMapper != null ? releaseMapper : ReleaseMapper.ALLPERMISSIVE, configuration, closeResolverOnClose);
    }

    @Override
    public void setMark(@Nonnull String mark, @Nullable Release rawRelease) throws RepositoryException, ReleaseChangeEventListener.ReplicationFailedException {
        ReleaseImpl release = Objects.requireNonNull(ReleaseImpl.unwrap(rawRelease));
        ResourceHandle releasesnode = ResourceHandle.use(release.getReleaseNode().getParent()); // the cpl:releases node
        // property type REFERENCE prevents deleting it accidentially
        releasesnode.setProperty(mark, release.getUuid(), PropertyType.REFERENCE);
        publisher.publishActivation(ReleaseChangeEvent.fullUpdate(release));
    }

    @Override
    public void deleteMark(@Nonnull String mark, @Nonnull Release rawRelease) throws RepositoryException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        ResourceHandle releasesnode = ResourceHandle.use(release.getReleaseNode().getParent()); // the cpl:releases node
        if (StringUtils.equals(mark, releasesnode.getProperty(mark, String.class))) {
            throw new IllegalArgumentException("Release does not carry mark " + mark + " : " + rawRelease);
        }
        releasesnode.setProperty(mark, (String) null);
    }

    @Override
    public void deleteRelease(@Nonnull Release rawRelease) throws RepositoryException, PersistenceException, ReleaseProtectedException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        if (!release.getMarks().isEmpty()) {
            throw new ReleaseProtectedException();
        }
        // check whether there are any releases pointing to this one as previous release. Reroute them to our predecessor.
        for (Release otherRelease : getReleasesImpl(release.getReleaseRoot())) {
            if (release.equals(otherRelease.getPreviousRelease())) {
                setPreviousRelease(otherRelease, release.getPreviousRelease());
            }
        }
        release.getReleaseRoot().getResourceResolver().delete(release.getReleaseNode());
        cleanupLabels(release.getReleaseRoot());
    }

    @Nullable
    @Override
    public Release findReleaseByMark(@Nullable Resource resource, @Nonnull String mark) {
        if (resource == null) {
            return null;
        }
        ResourceHandle root = findReleaseRoot(resource);
        ResourceHandle releasesNode = getReleasesNode(root);
        if (!releasesNode.isValid()) {
            return null;
        }
        String uuid = releasesNode.getProperty(mark, String.class);
        if (StringUtils.isBlank(uuid)) {
            return null;
        }
        for (Resource releaseNode : releasesNode.getChildren()) {
            if (uuid.equals(releaseNode.getValueMap().get(PROP_UUID, String.class))) {
                return new ReleaseImpl(root, releaseNode);
            }
        }
        return null;
    }

    @Override
    public Release findReleaseByReleaseResource(Resource releaseResource) {
        if (releaseResource == null) {
            return null;
        }
        for (Release release : getReleasesImpl(releaseResource)) {
            if (SlingResourceUtil.isSameOrDescendant(ReleaseImpl.unwrap(release).getReleaseNode().getPath(), releaseResource.getPath())) {
                return release;
            }
        }
        return null;
    }

    @Nonnull
    @Override
    public ReleasedVersionable restoreVersionable(@Nonnull Release rawRelease, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        listWorkspaceContents(release.getReleaseRoot())
                .forEach((existingVersionable) ->
                        Validate.isTrue(!StringUtils.equals(existingVersionable.getVersionHistory(), releasedVersionable.getVersionHistory()),
                                "Cannot restore versionable %s from relese %s that exists at %s", releasedVersionable.getVersionHistory(), release, existingVersionable.getRelativePath()));
        String newPath = release.absolutePath(releasedVersionable.getRelativePath());
        Session session = release.getReleaseRoot().getResourceResolver().adaptTo(Session.class);
        Version version = (Version) session.getNodeByIdentifier(releasedVersionable.getVersionUuid());
        session.getWorkspace().getVersionManager().restore(newPath, version, false);
        return ReleasedVersionable.forBaseVersion(release.getReleaseRoot().getResourceResolver().getResource(newPath));
    }

    /**
     * Remove all labels starting with {@value StagingConstants#RELEASE_LABEL_PREFIX} from version histories pointing
     * into our release root that do not name a release that actually exists.
     *
     * @return the number of broken labels
     */
    @Override
    public int cleanupLabels(@Nonnull Resource resource) throws RepositoryException {
        long start = System.currentTimeMillis();
        int count = 0;
        ResourceHandle root = findReleaseRoot(resource);

        Set<String> expectedLabels = getReleasesImpl(root).stream().map(Release::getReleaseLabel).collect(Collectors.toSet());

        Query query = root.getResourceResolver()
                .adaptTo(QueryBuilder.class)
                .createQuery();
        query.path("/jcr:system/jcr:versionStorage").type("nt:versionHistory").condition(
                query.conditionBuilder().property("default").like().val(root.getPath() + "/%")
        );
        for (Resource versionHistory : query.execute()) {
            Resource labelResource = versionHistory.getChild(ResourceUtil.JCR_VERSIONLABELS);
            ValueMap valueMap = labelResource.getValueMap();
            for (String label : valueMap.keySet()) {
                if (label.startsWith(RELEASE_LABEL_PREFIX) && !expectedLabels.contains(label)) {
                    Property versionProperty = valueMap.get(label, Property.class);
                    Node version = versionProperty.getNode();
                    VersionHistory versionHistoryNode = (VersionHistory) version.getParent();
                    versionHistoryNode.removeVersionLabel(label);
                    LOG.debug("Removing obsolete label {} from {}", label, labelResource.getPath());
                    count++;
                }
            }
        }
        LOG.info("cleanupLabels removed {} obsolete labels in {}s", 0.001 * (System.currentTimeMillis() - start));
        return count;
    }

    @Override
    public void closeRelease(@Nonnull Release rawRelease) throws RepositoryException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        if (release.getNumber().equals(CURRENT_RELEASE)) {
            throw new IllegalArgumentException("Current release cannot be closed.");
        }
        LOG.info("Closing release {}", release);
        ResourceHandle.use(release.getReleaseNode()).setProperty(PROP_CLOSED, true);
    }

    @Nonnull
    @Override
    public String bumpReleaseChangeNumber(@Nonnull Release rawRelease) throws RepositoryException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        String newChangeNumber = "chg" + Math.abs(random.nextLong());
        // Since this changes randomly, we don't have to be afraid of concurrent modifications.
        ModifiableValueMap modifiableValueMap = release.workspaceCopyNode.adaptTo(ModifiableValueMap.class);
        String oldChangeNumber = modifiableValueMap.get(StagingConstants.PROP_CHANGE_NUMBER, String.class);
        modifiableValueMap.put(StagingConstants.PROP_CHANGE_NUMBER, newChangeNumber);
        LOG.info("Updating release change number to {} from originally {}", newChangeNumber, oldChangeNumber);
        return newChangeNumber;
    }

    /**
     * Ensures the technical resources for a release are there. If the release is created, the root is completely empty.
     */
    protected ReleaseImpl ensureRelease(@Nonnull Resource theRoot, @Nonnull String releaseLabel) throws RepositoryException, PersistenceException {
        ResourceHandle root = ResourceHandle.use(theRoot);
        if (!root.isValid() && !root.isOfType(TYPE_MIX_RELEASE_ROOT)) {
            throw new IllegalArgumentException("Not a release root: " + theRoot.getPath());
        }

        ResourceHandle releasesNode = getReleasesNode(root);
        if (!releasesNode.isValid()) {
            releasesNode = ResourceHandle.use(
                    ResourceUtil.getOrCreateResource(root.getResourceResolver(), getReleasesNodePath(root), TYPE_UNSTRUCTURED));
        }

        Resource releaseNode = ResourceUtil.getOrCreateChild(releasesNode, releaseLabel, TYPE_UNSTRUCTURED);
        SlingResourceUtil.addMixin(releaseNode, TYPE_REFERENCEABLE);

        String[] history = releasesNode.getProperty(PROP_RELEASE_ROOT_HISTORY, new String[0]);
        if (!Arrays.asList(history).contains(theRoot.getPath())) {
            ArrayList<String> newHistory = new ArrayList<>(Arrays.asList(history));
            newHistory.add(theRoot.getPath());
            releasesNode.setProperty(PROP_RELEASE_ROOT_HISTORY, newHistory);
        }

        Resource releaseWorkspaceCopy = ResourceUtil.getOrCreateChild(releaseNode, NODE_RELEASE_ROOT, TYPE_UNSTRUCTURED);
        // set a frozen primary type to ensure a sane state
        ResourceHandle.use(releaseWorkspaceCopy).setProperty(ResourceUtil.JCR_FROZENPRIMARYTYPE, ResourceUtil.TYPE_SLING_ORDERED_FOLDER);

        ResourceHandle metaData = ResourceHandle.use(releaseNode.getChild(NODE_RELEASE_METADATA));
        if (!metaData.isValid()) {
            metaData = ResourceHandle.use(root.getResourceResolver().create(releaseNode, NODE_RELEASE_METADATA,
                    ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                            PROP_MIXINTYPES,
                            new String[]{TYPE_CREATED, TYPE_LAST_MODIFIED, TYPE_TITLE})));
        }

        return new ReleaseImpl(root, releaseNode); // incl. validation
    }

    /**
     * Pseudo- {@link ReleaseNumberCreator} that returns a given release name
     */
    @Nonnull
    ReleaseNumberCreator givenReleaseNumber(@Nonnull final String name) {
        return new ReleaseNumberCreator() {

            @Nonnull
            @Override
            public String name() {
                return "self";
            }

            @Nonnull
            @Override
            public String bumpRelease(@Nonnull String oldname) {
                return name;
            }
        };
    }


    public static class ReleaseImpl implements StagingReleaseManager.Release {

        @Nonnull
        final Resource releaseRoot;

        @Nonnull
        final Resource releaseNode;

        @Nonnull
        final Resource workspaceCopyNode;

        private List<String> marks;

        private transient Optional<ReleaseImpl> prevRelease;

        ReleaseImpl(@Nonnull Resource releaseRoot, @Nonnull Resource releaseNode) {
            this.releaseRoot = requireNonNull(releaseRoot);
            this.releaseNode = requireNonNull(releaseNode);
            this.workspaceCopyNode = requireNonNull(getReleaseNode().getChild(NODE_RELEASE_ROOT));
            validate();
        }

        /**
         * A quick sanity check that all needed nodes are there.
         */
        public void validate() {
            ResourceHandle root = ResourceHandle.use(releaseRoot);
            if (!root.isValid() && !root.isOfType(TYPE_MIX_RELEASE_ROOT)) {
                throw new IllegalArgumentException("Not a release root: " + releaseRoot.getPath());
            }
            if (!releaseNode.getPath().startsWith(RELEASE_ROOT_PATH + root.getPath() + "/" + NODE_RELEASES)) {
                throw new IllegalArgumentException("Suspicious release node " + releaseNode.getPath() + " in " + this);
            }
            if (releaseNode.getChild(NODE_RELEASE_METADATA) == null) {
                throw new IllegalArgumentException("No metadata node in " + this);
            }
        }

        @Override
        @Nonnull
        public String getUuid() {
            return ResourceHandle.use(releaseNode).getProperty(PROP_UUID);
        }

        @Override
        @Nonnull
        public String getNumber() {
            return releaseNode.getName();
        }

        @Override
        @Nonnull
        public String getPath() {
            return releaseNode.getPath();
        }

        @Override
        @Nonnull
        public Resource getReleaseRoot() {
            return releaseRoot;
        }

        @Override
        @Nonnull
        public Resource getMetaDataNode() {
            return requireNonNull(releaseNode.getChild(NODE_RELEASE_METADATA), "No metadata node on " + releaseNode.getPath());
        }

        @Nonnull
        @Override
        public String getChangeNumber() {
            String changeNumber = getWorkspaceCopyNode() != null ?
                    getWorkspaceCopyNode().getValueMap().get(StagingConstants.PROP_CHANGE_NUMBER, String.class) : null;
            if (StringUtils.isBlank(changeNumber)) { // only OK during a transition period
                LOG.warn("No change number set: {}", SlingResourceUtil.getPath(getWorkspaceCopyNode()));
                changeNumber = "chgunset"; // fake number satisfying @Nonnull; will be updated on next change.
            }
            return changeNumber;
        }

        @Nonnull
        @Override
        public List<String> getMarks() {
            if (marks == null) {
                marks = new ArrayList<>();
                for (Map.Entry entry : releaseNode.getParent().getValueMap().entrySet()) {
                    if (getUuid().equals(entry.getValue())) {
                        marks.add(String.valueOf(entry.getKey()));
                    }
                }
            }
            return Collections.unmodifiableList(marks);
        }

        @Override
        public boolean isClosed() {
            return releaseNode.getValueMap().get(PROP_CLOSED, false);
        }

        @Nullable
        @Override
        public Release getPreviousRelease() throws RepositoryException {
            if (prevRelease == null) {
                Resource prevReleaseResource = ResourceUtil.getReferredResource(releaseNode.getChild(PROP_PREVIOUS_RELEASE_UUID));
                prevRelease = Optional.ofNullable(prevReleaseResource != null ? new ReleaseImpl(releaseRoot, prevReleaseResource) : null);
            }
            return prevRelease.orElse(null);
        }

        /**
         * The resource that contains the data for the release - including the subnode {@value StagingConstants#NODE_RELEASE_ROOT}
         * with the copy of the data. Don't touch {@value StagingConstants#NODE_RELEASE_ROOT} - always use the
         * {@link StagingReleaseManager} for that!
         */
        @Nonnull
        public Resource getReleaseNode() {
            return releaseNode;
        }

        /**
         * The node that contains the root of workspace copy for the release.
         */
        @Nonnull
        public Resource getWorkspaceCopyNode() {
            return workspaceCopyNode;
        }

        @Override
        public boolean appliesToPath(@Nullable String path) {
            return SlingResourceUtil.isSameOrDescendant(releaseRoot.getPath(), path);
        }

        @Nonnull
        @Override
        public String absolutePath(@Nullable String path) {
            if (StringUtils.startsWith(path, "/")) {
                if (SlingResourceUtil.isSameOrDescendant(workspaceCopyNode.getPath(), path)) {
                    String relativePath = SlingResourceUtil.relativePath(workspaceCopyNode.getPath(), path);
                    return getReleaseRoot().getPath() + '/' + relativePath;
                } else if (appliesToPath(path)) {
                    return path;
                } else {
                    throw new IllegalArgumentException("Path does not belong to release:" + path);
                }
            }
            if (StringUtils.isBlank(path)) {
                return getReleaseRoot().getPath();
            }
            return getReleaseRoot().getPath() + '/' + path;
        }

        @Override
        public String toString() {
            return "Release('" + getNumber() + "'," + releaseRoot.getPath() + ")";
        }

        /**
         * This is used to unwrap release to be able to access implementation specific methods,
         * and performs a sanity check that the release is still there - that's also a weak
         * measure to ensure this was created in the {@link DefaultStagingReleaseManager}, not outside.
         */
        public static ReleaseImpl unwrap(@Nullable Release release) {
            if (release != null) {
                ((ReleaseImpl) release).validate();
            }
            return (ReleaseImpl) release;
        }

        protected void updateLastModified() throws RepositoryException {
            ResourceHandle metaData = ResourceHandle.use(getMetaDataNode());
            if (metaData.isOfType(TYPE_LAST_MODIFIED)) {
                metaData.setProperty(JCR_LASTMODIFIED, Calendar.getInstance());
                metaData.setProperty(CoreConstants.JCR_LASTMODIFIED_BY, getReleaseRoot().getResourceResolver().getUserID());
            }
        }

        /**
         * Maps paths pointing into the release content to the release content copy.
         *
         * @param path an absolute or relative path
         * @return if path is absolute, return the path in the releases content copy that corresponds to that path - we don't check whether it actually exists.
         * If it is relative, returns the path in the releases content copy that has that path relative to the release.
         */
        @Nonnull
        public String mapToContentCopy(@Nonnull String path) {
            if (path.startsWith("/")) {
                if (appliesToPath(path)) {
                    path = ResourceUtil.normalize(path);
                    if (releaseRoot.getPath().equals(path)) {
                        path = getWorkspaceCopyNode().getPath();
                    } else if (null != path) {
                        assert path.startsWith(releaseRoot.getPath());
                        path = getWorkspaceCopyNode().getPath() + '/'
                                + path.substring(releaseRoot.getPath().length() + 1);
                    }
                }
            } else { // relative path
                ResourceUtil.normalize(getWorkspaceCopyNode().getPath() + '/'
                        + path);
            }
            return path;
        }

        /**
         * Reverse of {@link #mapToContentCopy(String)}: reconstructs original path from a path pointing to the content copy.
         */
        @Nonnull
        public String unmapFromContentCopy(@Nonnull String contentCopyPath) {
            String path = ResourceUtil.normalize(contentCopyPath);
            if (SlingResourceUtil.isSameOrDescendant(getWorkspaceCopyNode().getPath(), path)) {
                path = getReleaseRoot().getPath() + path.substring(getWorkspaceCopyNode().getPath().length());
            }
            return path != null ? path : contentCopyPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ReleaseImpl release = (ReleaseImpl) o;
            return StringUtils.equals(releaseRoot.getPath(), release.getReleaseRoot().getPath()) &&
                    StringUtils.equals(releaseNode.getPath(), release.getReleaseNode().getPath());
        }

        @Nullable
        @Override
        public VersionReference versionReference(@Nullable String relativePath) {
            VersionReference result = null;
            if (relativePath != null) {
                Resource versionReference = getWorkspaceCopyNode().getChild(relativePath);
                if (versionReference != null) {
                    result = new VersionReferenceImpl(this, versionReference);
                }
            }
            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hash(releaseRoot, releaseNode);
        }
    }

    protected static class VersionReferenceImpl implements VersionReference {

        @Nonnull
        private final ReleaseImpl release;

        @Nonnull
        private final ResourceHandle versionReference;

        protected VersionReferenceImpl(@Nonnull ReleaseImpl release, @Nonnull Resource versionReference) {
            this.release = Objects.requireNonNull(release);
            this.versionReference = ResourceHandle.use(requireNonNull(versionReference));
            if (!isVersionReference(versionReference)) {
                throw new IllegalArgumentException("Not a version reference: " + versionReference.getPath());
            }
        }

        public static boolean isVersionReference(@Nonnull Resource versionReference) {
            return versionReference.isResourceType(TYPE_VERSIONREFERENCE);
        }

        @Override
        @Nonnull
        public ReleasedVersionable getReleasedVersionable() {
            return ReleasedVersionable.fromVersionReference(release.getWorkspaceCopyNode(), versionReference);
        }

        @Override
        public boolean isActive() {
            return !versionReference.getProperty(PROP_DEACTIVATED, false);
        }

        @Override
        @Nullable
        public Calendar getLastActivated() {
            Calendar lastActivated = versionReference != null ? versionReference.getProperty(PROP_LAST_ACTIVATED, Calendar.class) : null;
            return lastActivated;
        }

        @Override
        @Nullable
        public String getLastActivatedBy() {
            return versionReference != null ? versionReference.getProperty(PROP_LAST_ACTIVATED_BY, String.class) : null;
        }

        @Override
        @Nullable
        public Calendar getLastDeactivated() {
            return versionReference != null ? versionReference.getProperty(PROP_LAST_DEACTIVATED, Calendar.class) : null;
        }

        @Override
        @Nullable
        public String getLastDeactivatedBy() {
            return versionReference != null ? versionReference.getProperty(PROP_LAST_DEACTIVATED_BY, String.class) : null;
        }

        @Override
        @Nullable
        public Resource getVersionResource() {
            if (versionReference == null) {
                return null;
            }
            try {
                ResourceResolver resourceResolver = release.getReleaseRoot().getResourceResolver();
                return ResourceUtil.getByUuid(resourceResolver, getReleasedVersionable().getVersionUuid());
            } catch (RepositoryException | ClassCastException e) {
                throw new SlingException("Trouble accessing version " + getReleasedVersionable().getVersionUuid(), e);
            }
        }

        @Nullable
        @Override
        public Calendar getVersionCreated() {
            Resource versionResource = getVersionResource();
            return versionResource != null ? versionResource.getValueMap().get(JCR_CREATED, Calendar.class) : null;
        }

        @Override
        @Nonnull
        public StagingReleaseManager.Release getRelease() {
            return release;
        }

        @Nonnull
        @Override
        public String getPath() {
            return release.absolutePath(getReleasedVersionable().getRelativePath());
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("versionReference", SlingResourceUtil.getPath(versionReference))
                    .toString();
        }
    }

    @ObjectClassDefinition(
            name = "Composum Platform Staging Release Manager Configuration"
    )
    public @interface Configuration {

        @AttributeDefinition(
                name = "Overlayed Nodes",
                description = "Some nodes that are overlayed from the top level of the working content into the release"
        )
        String[] overlayed_nodes() default {};

        @AttributeDefinition(
                name = "Removed Paths",
                description = "Some paths that are removed form the overlayed nodes (relative to the release top level)"
        )
        String[] removed_paths() default {};

    }

}

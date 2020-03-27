package com.composum.sling.platform.staging.replication.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.PropertyUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.impl.NodeTreeSynchronizer;
import com.composum.sling.platform.staging.replication.ReplicationConstants;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import com.google.common.collect.ImmutableBiMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.fs.io.Importer;
import org.apache.jackrabbit.vault.fs.io.ZipStreamArchive;
import org.apache.sling.api.resource.*;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.composum.sling.core.util.SlingResourceUtil.appendPaths;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_REPLICATION_DATE;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

/**
 * Interface for service that implements the functions behind the {@link RemotePublicationReceiverServlet}.
 */
@Component(
        service = PublicationReceiverBackend.class,
        property = {Constants.SERVICE_DESCRIPTION + "=Composum Platform Publication Receiver Backend Service"},
        configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = PublicationReceiverBackendService.Configuration.class)
public class PublicationReceiverBackendService implements PublicationReceiverBackend {

    private static final Logger LOG = LoggerFactory.getLogger(PublicationReceiverBackendService.class);

    /**
     * Prefix to create metadata path for replicated content.
     */
    public static final String PATH_METADATA = "/var/composum/replication";

    protected volatile Configuration config;

    @Reference
    protected ResourceResolverFactory resolverFactory;

    /**
     * Random number generator for creating unique ids etc.
     */
    protected final Random random;

    /**
     * Debugging aid - if set to true, the temporary directory will not be deleted.
     */
    protected boolean nodelete = false;

    public PublicationReceiverBackendService() {
        Random therandom;
        try {
            therandom = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) { // should be pretty much impossible
            LOG.error("" + e, e);
            therandom = new Random();
        }
        random = therandom;
    }

    @Activate
    @Modified
    protected void activate(Configuration configuration) {
        this.config = configuration;
    }

    @Deactivate
    protected void deactivate() {
        this.config = null;
    }

    @Override
    public boolean isEnabled() {
        Configuration theconfig = this.config;
        return theconfig != null && theconfig.enabled();
    }

    @Override
    public String getChangeRoot() {
        return config.changeRoot();
    }

    @Override
    public UpdateInfo startUpdate(@Nonnull ReplicationPaths replicationPaths)
            throws PersistenceException, LoginException, RemotePublicationReceiverException, RepositoryException {
        LOG.info("Start update called for {}", replicationPaths);
        try (ResourceResolver resolver = makeResolver()) {
            ensureMetaResourceAndPath(replicationPaths, resolver);

            UpdateInfo updateInfo = new UpdateInfo();
            updateInfo.updateId = "upd-" + RandomStringUtils.random(12, 0, 0, true, true, null, random);
            assert ReplicationConstants.PATTERN_UPDATEID.matcher(updateInfo.updateId).matches();
            Resource tmpLocation = getTmpLocation(resolver, updateInfo.updateId, true);

            ModifiableValueMap vm = requireNonNull(tmpLocation.adaptTo(ModifiableValueMap.class));
            vm.put(ReplicationConstants.ATTR_TOP_CONTENTPATH, replicationPaths.getContentPath());
            vm.put(ReplicationConstants.ATTR_RELEASEROOT_PATH, replicationPaths.getReleaseRoot());
            if (replicationPaths.getSourcePath() != null) {
                vm.put(ReplicationConstants.ATTR_SRCPATH, replicationPaths.getSourcePath());
            }
            if (replicationPaths.getTargetPath() != null) {
                vm.put(ReplicationConstants.ATTR_TARGETPATH, replicationPaths.getTargetPath());
            }

            fillUpdateInfo(updateInfo, resolver, replicationPaths);
            if (StringUtils.isNotBlank(updateInfo.originalPublisherReleaseChangeId)) {
                vm.put(ReplicationConstants.ATTR_OLDPUBLISHERCONTENT_RELEASECHANGEID, updateInfo.originalPublisherReleaseChangeId);
            }
            resolver.commit();
            return updateInfo;
        }
    }

    protected void ensureMetaResourceAndPath(@Nonnull ReplicationPaths replicationPaths, ResourceResolver resolver) throws RepositoryException {
        // make sure the meta resource and target can be created
        Resource meta = Objects.requireNonNull(getMetaResource(resolver, replicationPaths, true));
        String destinationPath = appendPaths(config.changeRoot(), replicationPaths.getDestination());
        Resource destinationResource = resolver.getResource(destinationPath);
        if (destinationResource == null) {
            destinationResource = ResourceUtil.getOrCreateResource(resolver, destinationPath, ResourceUtil.TYPE_SLING_FOLDER);
            if (destinationResource != null) { // it wasn't just unreadable - reset change number since that was deleted
                meta.adaptTo(ModifiableValueMap.class).remove(StagingConstants.PROP_CHANGE_NUMBER);
            }
        }
    }

    protected void fillUpdateInfo(UpdateInfo updateInfo, ResourceResolver resolver, ReplicationPaths replicationPaths)
            throws RepositoryException {
        Resource metaResource = getMetaResource(resolver, replicationPaths, false);
        updateInfo.originalPublisherReleaseChangeId = getReleaseChangeId(metaResource);
        Calendar lastReplicationDate = metaResource != null ?
                metaResource.getValueMap().get(PROP_LAST_REPLICATION_DATE, Calendar.class)
                : null;
        updateInfo.lastReplication = lastReplicationDate != null ? lastReplicationDate.getTimeInMillis() : null;
    }

    @Nullable
    protected Resource getMetaResource(ResourceResolver resolver, ReplicationPaths replicationPaths, boolean createIfNecessary)
            throws RepositoryException {
        String metapath = appendPaths(PATH_METADATA, replicationPaths.getDestination());
        Resource resource = resolver.getResource(metapath);
        if (createIfNecessary && resource == null) {
            resource = ResourceUtil.getOrCreateResource(resolver, metapath, ResourceUtil.TYPE_SLING_FOLDER);
        }
        return resource;
    }

    @Nullable
    @Override
    public UpdateInfo releaseInfo(@Nonnull ReplicationPaths replicationPaths) throws LoginException, RepositoryException {
        UpdateInfo result;
        if (LOG.isDebugEnabled()) {
            LOG.debug("ReleaseInfo called for {}", replicationPaths);
        }
        try (ResourceResolver resolver = makeResolver()) {
            result = new UpdateInfo();
            fillUpdateInfo(result, resolver, replicationPaths);
        }
        return result;
    }

    @Nonnull
    @Override
    public List<String> compareContent(@Nullable ReplicationPaths replicationPaths, @Nullable String updateId,
                                       @Nonnull Stream<VersionableInfo> versionableInfos)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, IOException {
        LOG.info("Compare content {} - {}", updateId, replicationPaths);
        try (ResourceResolver resolver = makeResolver()) {
            ReplicationPaths usedReplicationPaths = replicationPaths;
            if (StringUtils.isNotBlank(updateId)) {
                Resource tmpLocation = getTmpLocation(resolver, updateId, false);
                ValueMap vm = tmpLocation.getValueMap();
                usedReplicationPaths = new ReplicationPaths(vm);
            }

            VersionableTree versionableTree = new VersionableTree();
            versionableTree.process(versionableInfos, usedReplicationPaths.getContentPath(),
                    usedReplicationPaths.translateMapping(config.changeRoot()), resolver);
            List<String> result = new ArrayList<>();
            result.addAll(versionableTree.getChangedPaths());
            result.addAll(versionableTree.getDeletedPaths());
            LOG.info("Different versionables: {}", result);
            return result;
        }
    }

    // The packages are made for the untranslated paths. We have to consider the targetPath later.
    @Override
    public void pathUpload(@Nullable String updateId, @Nonnull String packageRootPath, @Nonnull InputStream inputStream)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, IOException, ConfigurationException {
        LOG.info("Pathupload called for {} : {}", updateId, packageRootPath);
        try (ResourceResolver resolver = makeResolver()) {
            Resource tmpLocation = getTmpLocation(resolver, updateId, false);
            ModifiableValueMap vm = requireNonNull(tmpLocation.adaptTo(ModifiableValueMap.class));
            ReplicationPaths replicationPaths = new ReplicationPaths(vm);

            ZipStreamArchive archive = new ZipStreamArchive(inputStream);
            try {
                Session session = requireNonNull(resolver.adaptTo(Session.class));

                Importer importer = new Importer();
                importer.getOptions().setFilter(new DefaultWorkspaceFilter());
                archive.open(true);
                LOG.info("Importing {}", archive.getMetaInf().getProperties());
                importer.run(archive, session, tmpLocation.getPath());
                if (importer.hasErrors()) {
                    LOG.error("Aborting import on {} to {}: importer has errors. {}",
                            updateId, packageRootPath, archive.getMetaInf().getProperties());
                    throw new RemotePublicationReceiverException("Aborting: internal error importing on remote " +
                            "system - please consult the logfile.", RemotePublicationReceiverException.RetryAdvice.NO_AUTOMATIC_RETRY);
                }

                processMove(resolver, SlingResourceUtil.appendPaths(tmpLocation.getPath(), packageRootPath), replicationPaths);
                session.save();
            } finally {
                archive.close();
            }

            List<String> newPaths = new ArrayList<>(asList(vm.get(ReplicationConstants.ATTR_UPDATEDPATHS, new String[0])));
            newPaths.add(packageRootPath);
            vm.put(ReplicationConstants.ATTR_UPDATEDPATHS, newPaths.toArray(new String[0]));
            resolver.commit();
        }
    }

    protected void processMove(ResourceResolver resolver, String packageRootPath, ReplicationPaths replicationPaths) {
        if (replicationPaths.isMove()) {
            Resource resource = resolver.getResource(packageRootPath);
            if (resource != null) {
                LOG.info("Processing move of {} rel from {} to {}", packageRootPath, replicationPaths.getOrigin(), replicationPaths.getTargetPath());
                replicationPaths.getMovePostprocessor().postprocess(resource);
            }
        }
    }

    @Override
    public void commit(@Nonnull String updateId, @Nonnull Set<String> deletedPaths,
                       @Nonnull Iterable<ChildrenOrderInfo> childOrderings, String newReleaseChangeId)
            throws LoginException, RemotePublicationReceiverException, RepositoryException, PersistenceException {
        LOG.info("Commit called for {} : {}", updateId, deletedPaths);
        try (ResourceResolver resolver = makeResolver()) {
            Resource tmpLocation = getTmpLocation(resolver, updateId, false);
            ValueMap vm = tmpLocation.getValueMap();
            ReplicationPaths replicationPaths = new ReplicationPaths(vm);
            String topContentPath = vm.get(ReplicationConstants.ATTR_TOP_CONTENTPATH, String.class);
            String chRoot = requireNonNull(config.changeRoot());
            String @NotNull [] updatedPaths = vm.get(ReplicationConstants.ATTR_UPDATEDPATHS, new String[0]);
            Resource metaResource = getMetaResource(resolver, replicationPaths, true);
            ResourceUtil.getOrCreateResource(resolver,
                    SlingResourceUtil.appendPaths(chRoot, replicationPaths.getDestination()), ResourceUtil.TYPE_SLING_FOLDER);

            for (String deletedPath : deletedPaths) {
                if (!SlingResourceUtil.isSameOrDescendant(topContentPath, deletedPath)) { // safety check - Bug!
                    throw new IllegalArgumentException("Not subpath of " + topContentPath + " : " + deletedPath);
                }
                deletePath(resolver, tmpLocation, deletedPath, replicationPaths, chRoot);
            }

            @Nonnull String targetRootPath = appendPaths(chRoot, replicationPaths.getDestination());
            Resource targetRoot = ResourceUtil.getOrCreateResource(resolver, targetRootPath);

            for (String updatedPath : updatedPaths) {
                if (!SlingResourceUtil.isSameOrDescendant(topContentPath, updatedPath)) { // safety check - Bug!
                    throw new IllegalArgumentException("Not subpath of " + topContentPath + " : " + updatedPath);
                }
                if (!deletedPaths.contains(updatedPath)) {
                    // if it's deleted we needed to transfer a package for it, anyway, to update it's parents
                    // attributes. So it's in updatedPath, too, but doesn't need to be moved.
                    moveVersionable(resolver, tmpLocation, updatedPath, replicationPaths, chRoot);
                }
            }

            for (String deletedPath : deletedPaths) {
                removeOrphans(resolver, chRoot, replicationPaths.translate(deletedPath), targetRootPath);
            }

            int numorderings = 0;
            for (ChildrenOrderInfo childrenOrderInfo : childOrderings) {
                numorderings++;
                if (!SlingResourceUtil.isSameOrDescendant(replicationPaths.getOrigin(), childrenOrderInfo.getPath())) { // safety check - Bug!
                    throw new IllegalArgumentException("Not subpath of " + replicationPaths.getOrigin() + " : " + childrenOrderInfo);
                }
                String path = appendPaths(chRoot, replicationPaths.translate(childrenOrderInfo.getPath()));
                Resource resource = resolver.getResource(path);
                if (resource != null) {
                    adjustChildrenOrder(resource, childrenOrderInfo.getChildNames());
                } else { // bug or concurrent modification
                    LOG.error("Resource for childorder doesn't exist: {}", path);
                }
            }
            LOG.debug("Number of child orderings read for {} was {}", topContentPath, numorderings);

            ModifiableValueMap releaseRootVm = metaResource.adaptTo(ModifiableValueMap.class);
            releaseRootVm.put(StagingConstants.PROP_LAST_REPLICATION_DATE, Calendar.getInstance());
            releaseRootVm.put(StagingConstants.PROP_CHANGE_NUMBER, newReleaseChangeId);
            releaseRootVm.put(ReplicationConstants.PARAM_SOURCEPATH, replicationPaths.getOrigin());
            releaseRootVm.put(ReplicationConstants.PARAM_RELEASEROOT, replicationPaths.getReleaseRoot());
            if (!nodelete) {
                resolver.delete(tmpLocation);
            }
            resolver.commit();
        }
    }

    protected void adjustChildrenOrder(@Nonnull Resource resource, @Nonnull List<String> childNames) throws RepositoryException {
        LOG.debug("Checking children order for {}", SlingResourceUtil.getPath(resource));
        List<String> currentChildNames = StreamSupport.stream(resource.getChildren().spliterator(), false)
                .map(Resource::getName)
                .collect(Collectors.toList());
        if (!childNames.equals(currentChildNames)) {
            Node node = requireNonNull(resource.adaptTo(Node.class));
            try {
                for (String childName : childNames) {
                    try {
                        node.orderBefore(childName, null); // move to end of list
                    } catch (RepositoryException | RuntimeException e) {
                        LOG.error("Trouble reordering {} : {} from {}", SlingResourceUtil.getPath(resource),
                                childName, childNames);
                        throw e;
                    }
                }

                currentChildNames = StreamSupport.stream(resource.getChildren().spliterator(), false)
                        .map(Resource::getName)
                        .collect(Collectors.toList());
                if (!childNames.equals(currentChildNames)) { // Bug or concurrent modification at source side
                    LOG.error("Reordering failed for {} : {} but still got {}", resource.getPath(), childNames,
                            currentChildNames);
                }
            } catch (UnsupportedRepositoryOperationException e) { // should be impossible.
                LOG.error("Bug: Child nodes not orderable for {} type {}", resource.getPath(),
                        resource.getValueMap().get(ResourceUtil.PROP_PRIMARY_TYPE, String.class));
            }
        }
    }

    @Override
    public void abort(@Nullable String updateId) throws LoginException, RemotePublicationReceiverException,
            RepositoryException, PersistenceException {
        LOG.info("Abort called for {}", updateId);
        if (nodelete) {
            return;
        }
        try (ResourceResolver resolver = makeResolver()) {
            Resource tmpLocation = getTmpLocation(resolver, updateId, false);
            resolver.delete(tmpLocation);
            resolver.commit();
        }
    }

    /**
     * Move tmpLocation/updatedPath to targetRoot/updatedPath possibly copying parents if they don't exist.
     * We rely on that the paths have been checked by the caller to not go outside of the release, and that
     * the release in the target has been created.
     */
    protected void moveVersionable(@Nonnull ResourceResolver resolver, @Nonnull Resource tmpLocation,
                                   @Nonnull String updatedPath, @Nonnull ReplicationPaths replicationPaths, @Nonnull String chRoot)
            throws RepositoryException, PersistenceException {
        NodeTreeSynchronizer synchronizer = new NodeTreeSynchronizer();
        Resource source = tmpLocation.getChild(SlingResourceUtil.relativePath("/", replicationPaths.getOrigin()));
        Resource destination = requireNonNull(resolver.getResource(SlingResourceUtil.appendPaths(chRoot, replicationPaths.getDestination())));
        synchronizer.updateAttributes(ResourceHandle.use(source), ResourceHandle.use(destination), ImmutableBiMap.of());
        String relPath = ResourceUtil.getParent(SlingResourceUtil.relativePath(replicationPaths.getOrigin(), updatedPath));
        if (relPath != null) {
            for (String pathsegment : relPath.split("/")) {
                source = requireNonNull(source.getChild(pathsegment), updatedPath);
                destination = ResourceUtil.getOrCreateChild(destination, pathsegment, ResourceUtil.TYPE_SLING_FOLDER);
                synchronizer.updateAttributes(ResourceHandle.use(source), ResourceHandle.use(destination), ImmutableBiMap.of());
            }
        }
        String nodename = ResourceUtil.getName(updatedPath);
        source = requireNonNull(source.getChild(nodename), updatedPath);
        Resource destinationParent = destination;
        destination = destination.getChild(nodename);
        Session session = Objects.requireNonNull(destinationParent.getResourceResolver().adaptTo(Session.class));

        if (destination != null) {
            // can't replace the node since OAK wrongly thinks we changed protected attributes
            // see com.composum.platform.replication.remote.ReplacementStrategyExplorationTest.bugWithReplace
            // we copy the attributes and move the children, instead, so protected attributes stay the same.
            synchronizer.updateAttributes(ResourceHandle.use(source), ResourceHandle.use(destination), ImmutableBiMap.of());
            for (Resource previousChild : destination.getChildren()) {
                resolver.delete(previousChild);
            }
            for (Resource child : source.getChildren()) {
                session.move(child.getPath(), destination.getPath() + "/" + child.getName());
                // avoid resolver.move(child.getPath(), destination.getPath()); because brittle
            }
        } else {
            Resource sourceParent = source.getParent();
            // use JCR move because of OAK-bugs: this is sometimes treated as copy and delete, which even fails
            // should be resolver.move(source.getPath(), destinationParent.getPath());
            session.move(source.getPath(), destinationParent.getPath() + "/" + nodename);
            if (ResourceUtil.isFile(sourceParent) && !sourceParent.hasChildren()) {
                resolver.delete(sourceParent); // otherwise tmpdir would be inconsistent.
            }
        }

        LOG.info("Moved {} to {}", SlingResourceUtil.getPath(source),
                SlingResourceUtil.getPath(destinationParent) + "/" + nodename);
    }

    protected void deletePath(@Nonnull ResourceResolver resolver, @Nonnull Resource tmpLocation,
                              @Nonnull String deletedPath, ReplicationPaths replicationPaths, @Nonnull String chRoot) throws PersistenceException, RepositoryException {
        NodeTreeSynchronizer synchronizer = new NodeTreeSynchronizer();
        Resource source = tmpLocation.getChild(SlingResourceUtil.relativePath("/", replicationPaths.getOrigin()));
        Resource destination = requireNonNull(resolver.getResource(SlingResourceUtil.appendPaths(chRoot, replicationPaths.getDestination())));
        String relPath = ResourceUtil.getParent(SlingResourceUtil.relativePath(replicationPaths.getOrigin(), deletedPath));
        if (relPath != null) {
            for (String pathsegment : relPath.split("/")) {
                source = source.getChild(pathsegment);
                destination = destination.getChild(pathsegment);
                if (source == null || destination == null) {
                    break;
                }
                synchronizer.updateAttributes(ResourceHandle.use(source), ResourceHandle.use(destination), ImmutableBiMap.of());
            }
        }

        Resource deletedResource = resolver.getResource(appendPaths(chRoot, replicationPaths.translate(deletedPath)));
        if (deletedResource != null) {
            LOG.info("Deleting {}", deletedPath);
            resolver.delete(deletedResource);
        } else { // some problem with the algorithm!
            LOG.warn("Path to delete unexpectedly not present in content: {}", deletedPath);
        }
    }

    /**
     * Removes parent nodes of the deleted nodes that do not have any (versionable) children now.
     */
    protected void removeOrphans(@Nonnull ResourceResolver resolver, @Nonnull String chRoot,
                                 @Nonnull String deletedPath, @Nonnull String targetRootPath) throws PersistenceException {
        String originalPath = appendPaths(chRoot, deletedPath);
        Resource candidate = SlingResourceUtil.getFirstExistingParent(resolver, originalPath);
        while (candidate != null && SlingResourceUtil.isSameOrDescendant(targetRootPath, candidate.getPath())
                && !ResourceUtil.isNodeType(candidate, ResourceUtil.MIX_VERSIONABLE) && !candidate.hasChildren()) {
            Resource todelete = candidate;
            candidate = candidate.getParent();
            LOG.info("Remove orphaned node {}", todelete.getPath());
            resolver.delete(todelete);
        }
    }


    @Nonnull
    protected Resource getTmpLocation(@Nonnull ResourceResolver resolver, @Nullable String updateId, boolean create)
            throws RepositoryException, RemotePublicationReceiverException {
        cleanup(resolver);

        if (StringUtils.isBlank(updateId) || !ReplicationConstants.PATTERN_UPDATEID.matcher(updateId).matches()) {
            throw new IllegalArgumentException("Broken updateId: " + updateId);
        }
        String path = config.tmpDir() + "/" + updateId;
        Resource tmpLocation = resolver.getResource(path);
        if (tmpLocation == null) {
            if (create) {
                tmpLocation = ResourceUtil.getOrCreateResource(resolver, path);
                tmpLocation.adaptTo(ModifiableValueMap.class).put(ResourceUtil.PROP_MIXINTYPES,
                        new String[]{ResourceUtil.MIX_CREATED, ResourceUtil.MIX_LAST_MODIFIED});
            } else {
                throw new IllegalArgumentException("Unknown updateId " + updateId);
            }
        } else {
            ModifiableValueMap vm = tmpLocation.adaptTo(ModifiableValueMap.class);
            vm.put(ResourceUtil.PROP_LAST_MODIFIED, new Date());
            ReplicationPaths replicationPaths = new ReplicationPaths(vm);
            String originalReleaseChangeId = vm.get(ReplicationConstants.ATTR_OLDPUBLISHERCONTENT_RELEASECHANGEID, String.class);
            String releaseChangeId = getReleaseChangeId(getMetaResource(resolver, replicationPaths, false));
            if (releaseChangeId != null && !StringUtils.equals(releaseChangeId, originalReleaseChangeId)) {
                LoggerFactory.getLogger(getClass()).error("Release change id changed since beginning of update: {} to" +
                        " {} . Aborting.", originalReleaseChangeId, releaseChangeId);
                throw new RemotePublicationReceiverException("Release change Id changed since beginning of update - aborting " +
                        "transfer. Retryable.", RemotePublicationReceiverException.RetryAdvice.RETRY_IMMEDIATELY);
            }

        }
        return tmpLocation;
    }

    /**
     * Removes old temporary directories to make space. That shouldn't be necessary except in serious failure cases
     * like interrupted connection or crashed servers during a replication.
     * We assume a directory last touched more than a day ago needs
     * to be removed - the {@link ResourceUtil#PROP_LAST_MODIFIED} is changed on each access with {@link #getTmpLocation(ResourceResolver, String, boolean)}.
     */
    protected void cleanup(ResourceResolver resolver) {
        int cleanupDays = config.cleanupTmpdirDays();
        if (cleanupDays < 1 || StringUtils.length(config.tmpDir()) < 4) {
            return;
        }
        Resource tmpDir = resolver.getResource(config.tmpDir());
        if (tmpDir == null) { // impossible
            LOG.warn("Can't find temporary directory for cleanup: {}", config.tmpDir());
            return;
        }
        long expireTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(cleanupDays);
        for (Resource child : tmpDir.getChildren()) {
            if (ReplicationConstants.PATTERN_UPDATEID.matcher(child.getName()).matches()) {
                Date modificationDate = child.getValueMap().get(ResourceUtil.PROP_LAST_MODIFIED, Date.class);
                if (modificationDate == null) {
                    modificationDate = child.getValueMap().get(ResourceUtil.PROP_CREATED, Date.class);
                }
                if (modificationDate != null && modificationDate.getTime() < expireTime) {
                    LOG.error("Cleanup: needing to delete temporary directory not touched for {} days: {}",
                            cleanupDays, child.getPath());
                    try {
                        resolver.delete(child);
                    } catch (PersistenceException e) {
                        LOG.error("Error deleting " + child.getPath(), e);
                    }
                }
            }
        }
    }

    @Nonnull
    @Override
    public List<String> compareChildorderings(@Nonnull ReplicationPaths replicationPaths, @Nonnull Iterable<ChildrenOrderInfo> childOrderings)
            throws LoginException, RemotePublicationReceiverException, RepositoryException {
        LOG.info("Compare child orderings for {}", replicationPaths);
        List<String> result = new ArrayList<>();
        int read = 0;
        try (ResourceResolver resolver = makeResolver()) {
            String chRoot = requireNonNull(config.changeRoot());
            for (ChildrenOrderInfo childrenOrderInfo : childOrderings) {
                String targetPath = appendPaths(chRoot, replicationPaths.translate(childrenOrderInfo.getPath()));
                Resource resource = resolver.getResource(targetPath);
                if (resource != null) {
                    if (!equalChildrenOrder(resource, childrenOrderInfo.getChildNames())) {
                        result.add(childrenOrderInfo.getPath());
                    }
                } else {
                    LOG.debug("resource for compareChildorderings not found: {}", targetPath);
                    result.add(childrenOrderInfo.getPath());
                }
                read++;
            }
        }
        LOG.debug("Number of child orderings read for {} was {}", replicationPaths, read);
        return result;
    }

    protected boolean equalChildrenOrder(@Nonnull Resource resource, @Nonnull List<String> childNames) {
        LOG.debug("compare: {}, {}", SlingResourceUtil.getPath(resource), childNames);
        List<String> currentChildNames = StreamSupport.stream(resource.getChildren().spliterator(), false)
                .map(Resource::getName)
                .collect(Collectors.toList());
        boolean result = currentChildNames.equals(childNames);
        if (!result) {
            LOG.debug("different children order at {}", resource.getPath());
        }
        return result;
    }

    @Nonnull
    @Override
    public List<String> compareAttributes(@Nonnull ReplicationPaths replicationPaths, @Nonnull Iterable<NodeAttributeComparisonInfo> attributeInfos)
            throws LoginException, RemotePublicationReceiverException {
        LOG.info("Compare parent attributes for {}", replicationPaths);
        List<String> result = new ArrayList<>();
        int read = 0;
        try (ResourceResolver resolver = makeResolver()) {
            String chRoot = requireNonNull(config.changeRoot());
            for (NodeAttributeComparisonInfo attributeInfo : attributeInfos) {
                String targetPath = appendPaths(chRoot, replicationPaths.translate(attributeInfo.path));
                Resource resource = resolver.getResource(targetPath);
                if (resource != null) {
                    NodeAttributeComparisonInfo ourAttributeInfo =
                            NodeAttributeComparisonInfo.of(resource,
                                    replicationPaths.inverseTranslateMapping(config.changeRoot()));
                    if (!attributeInfo.equals(ourAttributeInfo)) {
                        result.add(attributeInfo.path);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Different attributes for {} : {}", attributeInfo.path,
                                    attributeInfo.difference(ourAttributeInfo));
                        }
                    }
                } else {
                    LOG.debug("resource for compareParentPaths not found: {}", targetPath);
                    result.add(attributeInfo.path);
                }
                read++;
            }
        }
        LOG.debug("Number of parent attribute infos read for {} was {}", replicationPaths, read);
        return result;

    }

    @Override
    public VersionableTree contentStatus(@Nonnull ReplicationPaths replicationPaths,
                                         @Nonnull Collection<String> paths, @Nonnull ResourceResolver resolver) {
        List<Resource> resources = paths.stream()
                .map(replicationPaths::trimToOrigin)
                .filter(Objects::nonNull)
                .map(replicationPaths.translateMapping(getChangeRoot()))
                .map(resolver::getResource)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        VersionableTree versionableTree = new VersionableTree();
        versionableTree.setSearchtreeRoots(resources);
        return versionableTree;
    }

    /**
     * Creates the service resolver used to update the content.
     */
    @Nonnull
    protected ResourceResolver makeResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(null);
    }

    @Nullable
    protected String getReleaseChangeId(@Nullable Resource metaResource) {
        return metaResource != null ? metaResource.getValueMap().get(StagingConstants.PROP_CHANGE_NUMBER, String.class) : null;
    }

    @ObjectClassDefinition(
            name = "Composum Platform Publication Receiver Backend Service",
            description = "Configures a service that receives release changes from the local or a remote system"
    )
    public @interface Configuration {

        @AttributeDefinition(
                description = "The general on/off switch for this service."
        )
        boolean enabled() default false;

        @AttributeDefinition(
                description = "Temporary directory to unpack received files."
        )
        String tmpDir() default "/tmp/composum/platform/remotereceiver";

        @AttributeDefinition(
                description = "Directory where the content is unpacked. For production use set to /, for testing e.g." +
                        " to /tmp/composum/platform/replicationtest to just have a temporary copy of the replicated content to manually " +
                        "inspect there."
        )
        String changeRoot() default "/";

        @AttributeDefinition(
                description = "Automatic removal of stale temporary directories used for replication after this many " +
                        "days. Normally they are removed immediately after completion / abort."
        )
        int cleanupTmpdirDays() default 1;
    }

}

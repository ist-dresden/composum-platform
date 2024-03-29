package com.composum.sling.platform.staging.replication.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.impl.NodeTreeSynchronizer;
import com.composum.sling.platform.staging.replication.ReplicationConstants;
import com.composum.sling.platform.staging.replication.ReplicationException;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
import com.composum.sling.platform.staging.replication.UpdateInfo;
import com.composum.sling.platform.staging.replication.json.ChildrenOrderInfo;
import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.staging.replication.json.VersionableInfo;
import com.composum.sling.platform.staging.replication.json.VersionableTree;
import com.google.common.collect.ImmutableBiMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.fs.io.Importer;
import org.apache.jackrabbit.vault.fs.io.ZipStreamArchive;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.composum.sling.core.util.SlingResourceUtil.appendPaths;
import static com.composum.sling.core.util.SlingResourceUtil.getPath;
import static com.composum.sling.platform.staging.StagingConstants.PROP_LAST_REPLICATION_DATE;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

/**
 * Service for {@link PublicationReceiverBackend} - the backend for replication into a JCR repository.
 */
@Component(
        service = PublicationReceiverBackend.class,
        property = {Constants.SERVICE_DESCRIPTION + "=Composum Platform Replication Receiver Backend Service"}
)
@Designate(ocd = PublicationReceiverBackendService.Configuration.class)
public class PublicationReceiverBackendService implements PublicationReceiverBackend {

    private static final Logger LOG = LoggerFactory.getLogger(PublicationReceiverBackendService.class);

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
        random = new SecureRandom();
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
    public UpdateInfo startUpdate(@NotNull ReplicationPaths replicationPaths)
            throws ReplicationException {
        LOG.info("Start update called for {}", replicationPaths);
        try (ResourceResolver resolver = makeResolver()) {
            ensureMetaResourceAndPath(replicationPaths, resolver);

            UpdateInfo updateInfo = new UpdateInfo();
            updateInfo.updateId = "upd-" + RandomStringUtils.random(12, 0, 0, true, true, null, random);
            assert ReplicationConstants.PATTERN_UPDATEID.matcher(updateInfo.updateId).matches();
            Resource tmpLocation = getTmpLocation(resolver, updateInfo.updateId, true, true);

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
        } catch (PersistenceException e) {
            throw new ReplicationException(Message.error("Error during commit: {}", e), e);
        }
    }

    protected void ensureMetaResourceAndPath(@NotNull ReplicationPaths replicationPaths, ResourceResolver resolver) throws ReplicationException {
        // make sure the meta resource and target can be created
        Resource meta = Objects.requireNonNull(getMetaResource(resolver, replicationPaths, true));
        String destinationPath = appendPaths(config.changeRoot(), replicationPaths.getDestination());
        Resource destinationResource = resolver.getResource(destinationPath);
        if (destinationResource == null) {
            try {
                destinationResource = ResourceUtil.getOrCreateResource(resolver, destinationPath, ResourceUtil.TYPE_SLING_FOLDER);
            } catch (RepositoryException e) {
                throw new ReplicationException(Message.error("Service user could not create resource {} in backend", destinationPath), e);
            }
            if (destinationResource != null) { // it wasn't just unreadable - reset change number since that was deleted
                meta.adaptTo(ModifiableValueMap.class).remove(StagingConstants.PROP_CHANGE_NUMBER);
            }
        }
    }

    protected void fillUpdateInfo(UpdateInfo updateInfo, ResourceResolver resolver, ReplicationPaths replicationPaths)
            throws ReplicationException {
        Resource metaResource = getMetaResource(resolver, replicationPaths, false);
        updateInfo.originalPublisherReleaseChangeId = getReleaseChangeId(metaResource);
        Calendar lastReplicationDate = metaResource != null ?
                metaResource.getValueMap().get(PROP_LAST_REPLICATION_DATE, Calendar.class)
                : null;
        updateInfo.lastReplication = lastReplicationDate != null ? lastReplicationDate.getTimeInMillis() : null;
    }

    @Nullable
    protected Resource getMetaResource(ResourceResolver resolver, ReplicationPaths replicationPaths, boolean createIfNecessary)
            throws ReplicationException {
        String metapath = appendPaths(ReplicationConstants.PATH_METADATA, replicationPaths.getDestination()) + ReplicationConstants.NODE_METADATA;
        Resource resource = resolver.getResource(metapath);
        if (createIfNecessary && resource == null) {
            try {
                resource = ResourceUtil.getOrCreateResource(resolver, metapath, ResourceUtil.TYPE_SLING_FOLDER + "/" + ResourceUtil.NT_UNSTRUCTURED);
            } catch (RepositoryException e) {
                throw new ReplicationException(Message.error("Service user could not create {} in backend", metapath), e);
            }
        }
        return resource;
    }

    @Nullable
    @Override
    public UpdateInfo releaseInfo(@NotNull ReplicationPaths replicationPaths) throws ReplicationException {
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

    @NotNull
    @Override
    public List<String> compareContent(@Nullable ReplicationPaths replicationPaths, @Nullable String updateId,
                                       @NotNull Stream<VersionableInfo> versionableInfos)
            throws ReplicationException {
        LOG.info("Compare content {} - {}", updateId, replicationPaths);
        try (ResourceResolver resolver = makeResolver()) {
            ReplicationPaths usedReplicationPaths = replicationPaths;
            if (StringUtils.isNotBlank(updateId)) {
                Resource tmpLocation = getTmpLocation(resolver, updateId, false, true);
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
    public void pathUpload(@Nullable String updateId, @NotNull String packageRootPath, @NotNull InputStream inputStream)
            throws ReplicationException {
        LOG.info("Pathupload called for {} : {}", updateId, packageRootPath);
        try (ResourceResolver resolver = makeResolver()) {
            Resource tmpLocation = getTmpLocation(resolver, updateId, false, true);
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
                setLoggingProgressTracker(importer);
                if (importer.hasErrors()) {
                    LOG.error("Aborting import on {} to {}: importer has errors. {}",
                            updateId, packageRootPath, archive.getMetaInf().getProperties());
                    throw new ReplicationException(Message.error("Aborting: internal error importing on remote " +
                            "system - please consult the logfile for details.").setPath(packageRootPath), null);
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
        } catch (IOException e) {
            throw new ReplicationException(
                    Message.error("Error reading package for {} in backend", packageRootPath).setPath(packageRootPath), e);
        } catch (ConfigurationException | RepositoryException e) {
            throw new ReplicationException(Message.error("Internal error on remote system: {}", e).setPath(packageRootPath), e);
        }
    }

    /**
     * If debugging is enabled, sets a progress tracker that logs everything on debug level.
     */
    protected void setLoggingProgressTracker(Importer importer) {
        if (LOG.isDebugEnabled()) {
            importer.getOptions().setListener(
                    new ProgressTrackerListener() {
                        @Override
                        public void onMessage(Mode mode, String action, String path) {
                            LOG.debug("{} : {} on {}", mode, action, path);
                        }

                        @Override
                        public void onError(Mode mode, String path, Exception e) {
                            LOG.error("Error at {} on {}", mode, path, e);
                        }
                    });
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
    public void commit(@NotNull String updateId, @NotNull Set<String> deletedPaths,
                       @NotNull Iterable<ChildrenOrderInfo> childOrderings, String newReleaseChangeId)
            throws ReplicationException {
        LOG.info("Commit called for {} : {}", updateId, deletedPaths);
        try (ResourceResolver resolver = makeResolver()) {
            Resource tmpLocation = getTmpLocation(resolver, updateId, false, true);
            ValueMap vm = tmpLocation.getValueMap();
            ReplicationPaths replicationPaths = new ReplicationPaths(vm);
            String topContentPath = vm.get(ReplicationConstants.ATTR_TOP_CONTENTPATH, String.class);
            String chRoot = requireNonNull(config.changeRoot());
            String @NotNull [] updatedPaths = vm.get(ReplicationConstants.ATTR_UPDATEDPATHS, new String[0]);
            Resource metaResource = getMetaResource(resolver, replicationPaths, true);
            String destinationPath = appendPaths(chRoot, replicationPaths.getDestination());
            try {
                ResourceUtil.getOrCreateResource(resolver,
                        destinationPath, ResourceUtil.TYPE_SLING_FOLDER);
            } catch (RepositoryException e) {
                throw new ReplicationException(Message.error("Error creating destination in backend: {}", destinationPath), e);
            }

            for (String deletedPath : deletedPaths) {
                if (!SlingResourceUtil.isSameOrDescendant(topContentPath, deletedPath)) { // safety check - Bug!
                    throw new IllegalArgumentException("Not subpath of " + topContentPath + " : " + deletedPath);
                }
                deletePath(resolver, tmpLocation, deletedPath, replicationPaths, chRoot);
            }

            @NotNull String targetRootPath = appendPaths(chRoot, replicationPaths.getDestination());
            try {
                Resource ignored = ResourceUtil.getOrCreateResource(resolver, targetRootPath);
            } catch (RepositoryException e) {
                throw new ReplicationException(Message.error("Error creating target path in backend - {}", targetRootPath), e);
            }

            for (String updatedPath : updatedPaths) {
                if (!SlingResourceUtil.isSameOrDescendant(topContentPath, updatedPath)) { // safety check - Bug!
                    throw new IllegalArgumentException("Not subpath of " + topContentPath + " : " + updatedPath);
                }
                if (!deletedPaths.contains(updatedPath)) {
                    // if it's deleted we needed to transfer a package for it, anyway, to update it's parents
                    // attributes. So it's in updatedPath, too, but doesn't need to be moved.
                    try {
                        moveVersionable(resolver, tmpLocation, updatedPath, replicationPaths, chRoot);
                    } catch (RepositoryException | PersistenceException e) {
                        throw new ReplicationException(Message.error("Error updating path").setPath(updatedPath), e);
                    }
                }
            }

            for (String deletedPath : deletedPaths) {
                try {
                    removeOrphans(resolver, chRoot, replicationPaths.translate(deletedPath), targetRootPath);
                } catch (PersistenceException e) {
                    throw new ReplicationException(Message.error("Error deleting path").setPath(deletedPath), e);
                }
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
                    try {
                        adjustChildrenOrder(resource, childrenOrderInfo.getChildNames());
                    } catch (RepositoryException | RuntimeException e) {
                        throw new ReplicationException(Message.error("Error adjusting children ordering").setPath(path), e);
                    }
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
                try {
                    resolver.delete(tmpLocation);
                } catch (PersistenceException e) {
                    throw new ReplicationException(Message.error("Error deleting temporary directory in backend: {}", destinationPath), e);
                }
            }
            try {
                resolver.commit();
            } catch (PersistenceException e) {
                throw new ReplicationException(Message.error("Error during commit in backend"), e);
            }
        }
    }

    protected void adjustChildrenOrder(@NotNull Resource resource, @NotNull List<String> childNames) throws RepositoryException {
        LOG.debug("Checking children order for {}", getPath(resource));
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
                        LOG.error("Trouble reordering {} : {} from {}", getPath(resource),
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
    public void abort(@Nullable String updateId) throws ReplicationException {
        LOG.info("Abort called for {}", updateId);
        if (nodelete) {
            return;
        }
        Resource tmpLocation = null;
        try (ResourceResolver resolver = makeResolver()) {
            tmpLocation = getTmpLocation(resolver, updateId, false, false);
            resolver.delete(tmpLocation);
            resolver.commit();
        } catch (PersistenceException e) {
            throw new ReplicationException(Message.error("Could not delete temporary location {} on backend",
                    getPath(tmpLocation)), e);
        }
    }

    /**
     * Move tmpLocation/updatedPath to targetRoot/updatedPath possibly copying parents if they don't exist.
     * We rely on that the paths have been checked by the caller to not go outside of the release, and that
     * the release in the target has been created.
     */
    protected void moveVersionable(@NotNull ResourceResolver resolver, @NotNull Resource tmpLocation,
                                   @NotNull String updatedPath, @NotNull ReplicationPaths replicationPaths, @NotNull String chRoot)
            throws RepositoryException, PersistenceException, ReplicationException {
        NodeTreeSynchronizer synchronizer = new NodeTreeSynchronizer();
        Resource source = tmpLocation.getChild(SlingResourceUtil.relativePath("/", replicationPaths.getOrigin()));
        Resource destination = requireNonNull(resolver.getResource(SlingResourceUtil.appendPaths(chRoot, replicationPaths.getDestination())));
        updateAttributes(synchronizer, source, destination);
        if (!SlingResourceUtil.isSameOrDescendant(replicationPaths.getOrigin(), updatedPath)) {
            throw new IllegalArgumentException("updatedPath should be child of origin.");
        }
        String relPath = ResourceUtil.getParent(SlingResourceUtil.relativePath(replicationPaths.getOrigin(), updatedPath));
        if (relPath != null) {
            for (String pathsegment : relPath.split("/")) {
                source = requireNonNull(source.getChild(pathsegment), updatedPath);
                destination = ResourceUtil.getOrCreateChild(destination, pathsegment, ResourceUtil.TYPE_SLING_FOLDER);
                updateAttributes(synchronizer, source, destination);
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
            updateAttributes(synchronizer, source, destination);
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

        LOG.info("Moved {} to {}", getPath(source), getPath(destinationParent) + "/" + nodename);
    }

    protected void deletePath(@NotNull ResourceResolver resolver, @NotNull Resource tmpLocation,
                              @NotNull String deletedPath, ReplicationPaths replicationPaths, @NotNull String chRoot) throws ReplicationException {
        NodeTreeSynchronizer synchronizer = new NodeTreeSynchronizer();
        Resource source = tmpLocation.getChild(SlingResourceUtil.relativePath("/", replicationPaths.getOrigin()));
        Resource destination = requireNonNull(resolver.getResource(SlingResourceUtil.appendPaths(chRoot, replicationPaths.getDestination())));
        if (!SlingResourceUtil.isSameOrDescendant(replicationPaths.getOrigin(), deletedPath)) {
            throw new IllegalArgumentException("deletedPath should be child of origin."); // Safety check against bugs.
        }
        if (source != null) { // possibly null if this is a partial replication of a path that doesn't exist in release
            updateAttributes(synchronizer, source, destination);
            String relPath = ResourceUtil.getParent(SlingResourceUtil.relativePath(replicationPaths.getOrigin(), deletedPath));
            if (relPath != null) {
                for (String pathsegment : relPath.split("/")) {
                    source = source.getChild(pathsegment);
                    destination = destination.getChild(pathsegment);
                    if (source == null || destination == null) {
                        break;
                    }
                    updateAttributes(synchronizer, source, destination);
                }
            }
        }

        Resource deletedResource = resolver.getResource(appendPaths(chRoot, replicationPaths.translate(deletedPath)));
        if (deletedResource != null) {
            LOG.info("Deleting {}", deletedPath);
            try {
                resolver.delete(deletedResource);
            } catch (PersistenceException e) {
                throw new ReplicationException(Message.error("Could not delete {} in backend", getPath(deletedResource)), e);
            }
        } else { // some problem with the algorithm!
            LOG.warn("Bug? Path to delete unexpectedly not present in content: {}", deletedPath);
        }
    }

    protected void updateAttributes(@NotNull NodeTreeSynchronizer synchronizer, @NotNull Resource source, @NotNull Resource destination) throws ReplicationException {
        try {
            synchronizer.updateAttributes(ResourceHandle.use(source), ResourceHandle.use(destination), ImmutableBiMap.of());
            //adjustLastModified(source, destination);
        } catch (RepositoryException | RuntimeException e) {
            throw new ReplicationException(Message.error("Error during synchronization of attributes of {} in backend", getPath(destination)), e);
        }
    }

    /**
     * copies the jcr:lastModified or the jcr:created date of the source to the destination as jcr:lastModified
     *
    protected void adjustLastModified(@NotNull Resource source, @NotNull Resource destination) {
        if (ResourceUtil.isNodeType(destination, "mix:lastModified")) {
            Calendar modificationDate = getModificationDate(source);
            if (modificationDate != null) {
                ModifiableValueMap destValues = destination.adaptTo(ModifiableValueMap.class);
                if (destValues != null) {
                    destValues.put(ResourceUtil.PROP_LAST_MODIFIED, modificationDate);
                / *
                String lastModifiedBy = source.getValueMap().get(ResourceUtil.PROP_LAST_MODIFIED + "By", String.class);
                if (lastModifiedBy != null) {
                    destValues.put(ResourceUtil.PROP_LAST_MODIFIED + "By", lastModifiedBy);
                }
                * /
                }
            }
        }
    }
    */

    protected Calendar getModificationDate(Resource resource) {
        ValueMap values = resource.getValueMap();
        Calendar modificationDate = values.get(ResourceUtil.PROP_LAST_MODIFIED, Calendar.class);
        if (modificationDate == null) {
            modificationDate = values.get(ResourceUtil.PROP_CREATED, Calendar.class);
        }
        return modificationDate;
    }

    /**
     * Removes parent nodes of the deleted nodes that do not have any (versionable) children now.
     */
    protected void removeOrphans(@NotNull ResourceResolver resolver, @NotNull String chRoot,
                                 @NotNull String deletedPath, @NotNull String targetRootPath) throws PersistenceException {
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


    @NotNull
    protected Resource getTmpLocation(@NotNull ResourceResolver resolver, @Nullable String updateId, boolean create, boolean checkReleaseId)
            throws ReplicationException {
        cleanup(resolver);

        if (StringUtils.isBlank(updateId) || !ReplicationConstants.PATTERN_UPDATEID.matcher(updateId).matches()) {
            throw new IllegalArgumentException("Broken updateId: " + updateId);
        }
        String path = config.tmpDir() + "/" + updateId;
        Resource tmpLocation = resolver.getResource(path);
        if (tmpLocation == null) {
            if (create) {
                try {
                    tmpLocation = ResourceUtil.getOrCreateResource(resolver, path);
                } catch (RepositoryException | RuntimeException e) {
                    throw new ReplicationException(Message.error("Could not create temporary location {} in backend : {}", path, e.getMessage()), e);
                }
                tmpLocation.adaptTo(ModifiableValueMap.class).put(ResourceUtil.PROP_MIXINTYPES,
                        new String[]{ResourceUtil.MIX_CREATED, ResourceUtil.MIX_LAST_MODIFIED});
            } else {
                throw new IllegalArgumentException("Unknown updateId " + updateId);
            }
        } else if (checkReleaseId) {
            ModifiableValueMap vm = tmpLocation.adaptTo(ModifiableValueMap.class);
            vm.put(ResourceUtil.PROP_LAST_MODIFIED, new Date());
            ReplicationPaths replicationPaths = new ReplicationPaths(vm);
            String originalReleaseChangeId = vm.get(ReplicationConstants.ATTR_OLDPUBLISHERCONTENT_RELEASECHANGEID, String.class);
            String releaseChangeId = getReleaseChangeId(getMetaResource(resolver, replicationPaths, false));
            if (releaseChangeId != null && !StringUtils.equals(releaseChangeId, originalReleaseChangeId)) {
                LoggerFactory.getLogger(getClass()).error("Release change id changed since beginning of update: {} to" +
                        " {} . Aborting.", originalReleaseChangeId, releaseChangeId);
                throw new ReplicationException(Message.warn("Release change Id changed since beginning of update - aborting " +
                        "transfer. Retryable."), null).asRetryable();
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
                Calendar modificationDate = getModificationDate(child);
                if (modificationDate != null && modificationDate.getTime().getTime() < expireTime) {
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

    @NotNull
    @Override
    public List<String> compareChildorderings(@NotNull ReplicationPaths replicationPaths, @NotNull Iterable<ChildrenOrderInfo> childOrderings)
            throws ReplicationException {
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

    protected boolean equalChildrenOrder(@NotNull Resource resource, @NotNull List<String> childNames) {
        LOG.debug("compare: {}, {}", getPath(resource), childNames);
        List<String> currentChildNames = StreamSupport.stream(resource.getChildren().spliterator(), false)
                .map(Resource::getName)
                .collect(Collectors.toList());
        boolean result = currentChildNames.equals(childNames);
        if (!result) {
            LOG.debug("different children order at {}", resource.getPath());
        }
        return result;
    }

    @NotNull
    @Override
    public List<String> compareAttributes(@NotNull ReplicationPaths replicationPaths, @NotNull Iterable<NodeAttributeComparisonInfo> attributeInfos)
            throws ReplicationException {
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
    public VersionableTree contentStatus(@NotNull ReplicationPaths replicationPaths,
                                         @NotNull Collection<String> paths, @NotNull ResourceResolver resolver) {
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
    @NotNull
    protected ResourceResolver makeResolver() throws ReplicationException {
        try {
            return resolverFactory.getServiceResourceResolver(null);
        } catch (LoginException e) {
            throw new ReplicationException(Message.error("Could not get service user for backend"), e);
        }
    }

    @Nullable
    protected String getReleaseChangeId(@Nullable Resource metaResource) {
        return metaResource != null ? metaResource.getValueMap().get(StagingConstants.PROP_CHANGE_NUMBER, String.class) : null;
    }

    @ObjectClassDefinition(
            name = "Composum Platform Replication Receiver Backend Service",
            description = "Configures a service that receives release changes from the local or a remote system"
    )
    public @interface Configuration {

        @AttributeDefinition(
                description = "The general on/off switch for this service."
        )
        boolean enabled() default true;

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

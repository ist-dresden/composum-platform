package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.resource.*;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.TYPE_SLING_FOLDER;
import static com.composum.sling.core.util.SlingResourceUtil.appendPaths;
import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;
import static com.composum.sling.platform.staging.ReleaseChangeProcess.ReleaseChangeProcessorState.*;
import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.jackrabbit.JcrConstants.NT_UNSTRUCTURED;

/**
 * Base class for services that use the {@link ReplicatorStrategy} to replicate staged content.
 * This applies to both in-place replication on the current server, as well as to remote replication.
 */
public abstract class AbstractReplicationService<CONFIG extends AbstractReplicationConfig,
        PROCESS extends AbstractReplicationService.ReplicationProcess> implements ReleaseChangeEventListener {

    public static final String PATH_CONFIGROOT = "/conf";
    public static final String DIR_REPLICATION = "/replication";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractReplicationService.class);

    /**
     * Maps the path to the replication configuration to the process.
     */
    protected final Map<String, PROCESS> processesCache = Collections.synchronizedMap(new HashMap<>());

    @Nonnull
    @Override
    public Collection<PROCESS> processesFor(@Nullable Release release) {
        if (release == null || !isEnabled()) {
            return Collections.emptyList();
        }

        Collection<PROCESS> result =
                processesFor(release.getReleaseRoot()).stream()
                        .map(p -> (PROCESS) p)
                        .filter(process -> process.appliesTo(release))
                        .collect(Collectors.toList());
        return result;
    }

    /**
     * Gives the replication configurations for our configuration type for the release at releaseRoot.
     */
    @Nonnull
    protected List<CONFIG> getReplicationConfigs(@Nonnull Resource releaseRoot,
                                                 @Nonnull BeanContext context) {
        String releasePath = releaseRoot.getPath();
        String configparent = getConfigParent(releasePath);

        List<CONFIG> configs = new ArrayList<>();
        Resource configroot = releaseRoot.getResourceResolver().getResource(configparent);
        if (configroot != null) {
            for (Resource child : configroot.getChildren()) {
                if (getReplicationType().getServiceId().equals(child.getValueMap().get(ReplicationConfig.PN_REPLICATION_TYPE))) {
                    CONFIG replicationConfig =
                            context.withResource(child).adaptTo(getReplicationConfigClass());
                    if (replicationConfig != null) {
                        configs.add(replicationConfig);
                    }
                }
            }
        }
        return configs;
    }

    /**
     * Returns the directory where replication configurations are stored.
     */
    @Nonnull
    protected String getConfigParent(String releasePath) {
        return PATH_CONFIGROOT + releasePath + DIR_REPLICATION;
    }

    @Nonnull
    @Override
    public Collection<PROCESS> processesFor(@Nullable Resource resource) {
        if (resource == null || !isEnabled()) {
            return Collections.emptyList();
        }

        ResourceResolver resolver = resource.getResourceResolver();
        Resource releaseRoot;
        try {
            releaseRoot = getReleaseManager().findReleaseRoot(resource);
        } catch (StagingReleaseManager.ReleaseRootNotFoundException e) {
            return Collections.emptyList();
        }
        BeanContext context = new BeanContext.Service(resolver);

        List<CONFIG> replicationConfigs = getReplicationConfigs(releaseRoot, context);
        Collection<PROCESS> processes = new ArrayList<>();
        for (CONFIG replicationConfig : replicationConfigs) {
            PROCESS process = processesCache.computeIfAbsent(replicationConfig.getPath(),
                    (k) -> makePublishingProcess(releaseRoot, replicationConfig)
            );
            process.readConfig(replicationConfig, releaseRoot);
            processes.add(process);
        }
        return processes;
    }

    @Nonnull
    protected abstract PROCESS makePublishingProcess(Resource releaseRoot, CONFIG replicationConfig);

    /**
     * Returns CONFIG.class .
     */
    @Nonnull
    protected abstract Class<CONFIG> getReplicationConfigClass();

    /**
     * The replication type this service works on.
     */
    @Nonnull
    protected abstract ReplicationType getReplicationType();

    /**
     * Creates the service resolver used to update the content.
     */
    @Nonnull
    protected ResourceResolver makeResolver() throws ReplicationException {
        try {
            return getResolverFactory().getServiceResourceResolver(null);
        } catch (LoginException e) {
            throw new ReplicationException(Message.error("Could not create service resolver in replication service"), e);
        }
    }

    @Deactivate
    protected void deactivate() throws IOException {
        LOG.info("deactivated");
        Collection<PROCESS> processes = new ArrayList<>(processesCache.values());
        processesCache.clear();
        boolean allAborted = true;
        for (PROCESS process : processes) {
            allAborted = process.abort(false) && allAborted;
        }
        if (!allAborted) {
            try { // wait a little to hopefully allow safe shutdown with resource cleanup, removing remote stuff
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // shouldn't happen
            }
            for (PROCESS process : processes) {
                process.abort(true);
            }
        }
    }

    protected abstract boolean isEnabled();

    protected abstract StagingReleaseManager getReleaseManager();

    protected abstract ResourceResolverFactory getResolverFactory();


    /**
     * Internal interface for use with {@link AbstractReplicationService} and descendants.
     */
    protected static interface ReplicationProcess extends ReleaseChangeProcess {

        boolean abort(boolean hard);

        /**
         * Reads the configuration from this. The actual type of {replicationConfig} is CONFIG, but we cannot
         * do that because the type parameters would be getting out of hand.
         */
        void readConfig(@Nonnull AbstractReplicationConfig replicationConfig, @Nonnull Resource releaseRoot);

        boolean appliesTo(Release release);
    }

    public abstract class AbstractReplicationProcess implements ReplicationProcess {

        protected final Object changedPathsChangeLock = new Object();
        protected volatile MessageContainer messages = new MessageContainer(/*LoggerFactory.getLogger(getClass())*/);
        // we deliberately save nothing that refers to resolvers, since this is an object that lives long
        @Nonnull
        protected volatile String configPath;
        protected volatile String title;
        protected volatile String description;
        protected volatile String mark;
        protected volatile Long finished;
        protected volatile Long startedAt;
        protected volatile ReleaseChangeProcessorState state = idle;
        protected volatile ReplicatorStrategy runningStrategy;
        protected volatile Thread runningThread;
        protected volatile boolean enabled;
        /**
         * Forces content comparison instead of quick check.
         */
        protected volatile boolean forceCheck;
        @Nonnull
        protected volatile Set<String> changedPaths = new LinkedHashSet<>();
        @Nonnull
        protected volatile String releaseRootPath;
        protected volatile Boolean active;
        protected volatile Boolean hasRelease;
        protected volatile String releaseUuid;
        protected volatile ReplicationConfig cachedConfig;

        protected AbstractReplicationProcess(@Nonnull Resource releaseRoot, @Nonnull CONFIG config) {
            releaseRootPath = releaseRoot.getPath();
            readConfig(config, releaseRoot);
        }

        protected void abortAlreadyRunningStrategy() throws InterruptedException {
            if (runningStrategy != null) {
                LOG.error("Bug: Strategy already running in parallel? How can that be? {}", runningStrategy);
                runningStrategy.setAbortAtNextPossibility();
                Thread.sleep(5000);
                if (runningThread != null) {
                    runningThread.interrupt();
                    Thread.sleep(2000);
                }
            }
        }

        @Override
        public boolean abort(boolean hard) {
            boolean isNotRunning = true;
            synchronized (changedPathsChangeLock) {
                ReplicatorStrategy runningStrategyCopy = runningStrategy;
                if (runningStrategyCopy != null) {
                    if (hard) {
                        runningStrategy = null;
                    }
                    runningStrategyCopy.setAbortAtNextPossibility();
                    isNotRunning = false;
                    state = aborted;
                    updateHistory();
                }
                if (hard) {
                    Thread runningThreadCopy = runningThread;
                    if (runningThreadCopy != null) {
                        runningThread = null;
                        runningThreadCopy.interrupt();
                        isNotRunning = true;
                    }
                }
            }
            return isNotRunning;
        }

        @Nonnull
        @Override
        public MessageContainer getMessages() {
            return messages;
        }

        @Override
        public String getId() {
            return configPath;
        }

        @Override
        public String getName() {
            return title;
        }

        @Override
        public String getStage() {
            return mark.toLowerCase();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public int getCompletionPercentage() {
            switch (state) {
                case processing:
                    return runningStrategy != null ? runningStrategy.getProgress() : 0;
                case success:
                case error:
                    return 100;
                case idle:
                case awaiting:
                case disabled:
                default:
                    return 0;
            }
        }

        @Nonnull
        @Override
        public ReleaseChangeProcessorState getState() {
            return state;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        @Nonnull
        public String getReleaseRootPath() {
            return releaseRootPath;
        }

        /**
         * Removes paths that are contained in other paths.
         */
        @Nonnull
        protected Set<String> cleanupPaths(@Nonnull Iterable<String> paths) {
            Set<String> cleanedPaths = new LinkedHashSet<>();
            for (String path : paths) {
                if (cleanedPaths.stream().anyMatch((p) -> isSameOrDescendant(p, path))) {
                    continue;
                }
                cleanedPaths.removeIf((p) -> isSameOrDescendant(path, p));
                cleanedPaths.add(path);
            }
            return cleanedPaths;
        }

        /**
         * Returns the current paths in {@link #changedPaths} resetting {@link #changedPaths}.
         *
         * @param forceCheckSwapped
         */
        @Nonnull
        protected Set<String> swapOutChangedPaths(boolean[] forceCheckSwapped) {
            Set<String> processedChangedPaths;
            synchronized (changedPathsChangeLock) {
                processedChangedPaths = changedPaths;
                changedPaths = new LinkedHashSet<>();
                forceCheckSwapped[0] = forceCheck;
                forceCheck = false;
            }
            return processedChangedPaths;
        }

        /**
         * Adds unprocessed paths which were taken out of {@link #changedPaths} by {@link #swapOutChangedPaths(boolean[])}
         * back into the {@link #changedPaths}.
         */
        protected void addBackChangedPaths(Set<String> unProcessedChangedPaths, boolean[] forceCheckSwapped) {
            if (!unProcessedChangedPaths.isEmpty()) { // add them back
                synchronized (changedPathsChangeLock) {
                    if (changedPaths.isEmpty()) {
                        changedPaths = unProcessedChangedPaths;
                    } else { // some events arrived in the meantime
                        unProcessedChangedPaths.addAll(changedPaths);
                        changedPaths = cleanupPaths(unProcessedChangedPaths);
                        if (!changedPaths.isEmpty()) {
                            state = awaiting;
                        }
                    }
                    forceCheck = forceCheck || forceCheckSwapped[0];
                }
            }
        }

        @Override
        public boolean isActive() {
            if (!isEnabled() || StringUtils.isBlank(mark) || StringUtils.isBlank(releaseRootPath)) {
                return false;
            }
            if (active == null) {
                try (ResourceResolver serviceResolver = makeResolver()) {
                    Resource releaseRoot = serviceResolver.getResource(releaseRootPath);
                    Release release = releaseRoot != null ? getReleaseManager().findReleaseByMark(releaseRoot, mark) : null;
                    active = release != null;
                } catch (ReplicationException e) {
                    LOG.error("Can't get service resolver: " + e, e);
                }
            }
            return Boolean.TRUE.equals(active);
        }

        /**
         * Called as often as possible to adapt to config changes.
         */
        @Override
        public void readConfig(@Nonnull AbstractReplicationConfig replicationConfig, @Nonnull Resource releaseRoot) {
            configPath = requireNonNull(replicationConfig.getPath());
            title = replicationConfig.getTitle();
            description = replicationConfig.getDescription();
            enabled = replicationConfig.isEnabled();
            mark = replicationConfig.getStage();
            active = null;
            cachedConfig = replicationConfig;
            checkReleaseExists(replicationConfig, releaseRoot);
        }

        protected void checkReleaseExists(AbstractReplicationConfig replicationConfig, Resource releaseRoot) {
            hasRelease = getReleaseManager().findReleaseByMark(releaseRoot, mark) != null;
        }

        @Override
        public boolean hasRelease() {
            return hasRelease;
        }

        @Override
        public boolean appliesTo(Release release) {
            ResourceResolver resolver = release.getReleaseRoot().getResourceResolver();
            CONFIG publicationConfig = getRefreshedConfig(resolver);
            List<String> marks = release.getMarks();
            return publicationConfig != null && publicationConfig.isEnabled() && publicationConfig.getStage() != null &&
                    (marks.contains(publicationConfig.getStage().toLowerCase())
                            || marks.contains(publicationConfig.getStage().toUpperCase()));
        }

        protected CONFIG getRefreshedConfig(ResourceResolver resolver) {
            if (cachedConfig != null && cachedConfig.isImplicit()) {
                return (CONFIG) cachedConfig;
            }
            Resource configResource = resolver.getResource(configPath);
            return new BeanContext.Service(resolver).withResource(configResource)
                    .adaptTo(getReplicationConfigClass());
        }

        @Override
        public void triggerProcessing(@Nonnull ReleaseChangeEvent event) {
            if (!isEnabled()) {
                return;
            }
            if (!appliesTo(event.release())) { // shouldn't even be called.
                LOG.warn("Received event for irrelevant release {}", event);
                return;
            }
            LOG.info("adding event {}", event);

            boolean restart = !StringUtils.equals(releaseUuid, event.release().getUuid()) || getState() == error;
            releaseUuid = event.release().getUuid();

            synchronized (changedPathsChangeLock) {
                Set<String> newChangedPaths = new LinkedHashSet<>(changedPaths);
                newChangedPaths.addAll(event.newOrMovedResources());
                newChangedPaths.addAll(event.removedOrMovedResources());
                newChangedPaths.addAll(event.updatedResources());
                newChangedPaths = cleanupPaths(newChangedPaths);
                if (!newChangedPaths.equals(changedPaths)) {
                    restart = true;
                    changedPaths = newChangedPaths;
                }
                forceCheck = forceCheck || event.getForceCheck();
            }

            restart = restart || (!changedPaths.isEmpty() && runningStrategy == null);

            if (restart) {
                if (runningStrategy != null) {
                    runningStrategy.setAbortAtNextPossibility();
                }
                state = ReleaseChangeProcessorState.awaiting;
                startedAt = null;
                finished = null;
                messages = new MessageContainer(LoggerFactory.getLogger(getClass()));
            }
        }

        @Override
        public void runReplication() {
            if (getState() != ReleaseChangeProcessorState.awaiting || changedPaths.isEmpty() || !isEnabled()) {
                LOG.info("Nothing to do in replication {} state {}", getId(), getState());
                return;
            }
            state = ReleaseChangeProcessorState.processing;
            startedAt = System.currentTimeMillis();
            ReplicatorStrategy strategy = null;
            messages.clear();
            try (ResourceResolver serviceResolver = makeResolver()) {

                LOG.info("Starting run of replication {}", getId());
                updateHistory();

                boolean[] forceCheckSwapped = new boolean[1];
                Set<String> processedChangedPaths = swapOutChangedPaths(forceCheckSwapped);
                try {
                    strategy = makeReplicatorStrategy(serviceResolver, processedChangedPaths, forceCheckSwapped[0]);
                    if (strategy == null) {
                        messages.add(Message.error("Cannot create strategy - probably disabled"));
                        return;
                    }
                    abortAlreadyRunningStrategy();

                    runningStrategy = strategy;
                    startedAt = System.currentTimeMillis();
                    try {
                        runningThread = Thread.currentThread();
                        runningStrategy.replicate();
                    } finally {
                        runningThread = null;
                    }
                    state = success;
                    processedChangedPaths.clear();
                    forceCheckSwapped[0] = false;
                } finally {
                    addBackChangedPaths(processedChangedPaths, forceCheckSwapped);
                }

            } catch (InterruptedException e) {
                LOG.error("Interrupted " + e, e);
                messages.add(Message.warn("Interrupted"), e);
            } catch (RuntimeException e) {
                messages.add(Message.error("Other error: ", e.toString()), e);
            } catch (ReplicationException e) {
                LOG.error("Error during runReplication: " + e, e);
                messages.addAll(e.getMessages());
            } finally {
                //noinspection ObjectEquality : reset if there wasn't a new strategy created in the meantime
                if (runningStrategy == strategy) {
                    runningStrategy = null;
                }
                if (getState() != success && getState() != awaiting) {
                    state = error;
                }
                finished = System.currentTimeMillis();
                LOG.info("Finished run with {} : {} - @{}", getState(), getId(), System.identityHashCode(this));
                updateHistory();
            }
        }

        @Override
        @Nullable
        public ReleaseChangeEventPublisher.CompareResult compareTree(
                @Nonnull ResourceHandle resource, int details) throws ReplicationException {
            if (!isEnabled()) {
                return null;
            }
            ReplicatorStrategy strategy = makeReplicatorStrategy(resource.getResourceResolver(), Collections.singleton(resource.getPath()), true);
            if (strategy == null) {
                return null;
            }
            return strategy.compareTree(details);
        }

        @Nonnull
        protected ReplicatorStrategy makeReplicatorStrategy(ResourceResolver serviceResolver, Set<String> processedChangedPaths, boolean forceCheck)
                throws ReplicationException {
            CONFIG replicationConfig = getRefreshedConfig(serviceResolver);
            if (replicationConfig == null || !replicationConfig.isEnabled()) {
                LOG.warn("Disabled / unreadable config, not run: {}", getId());
                return null; // warning - should normally have been caught before
            }

            Resource releaseRoot = requireNonNull(serviceResolver.getResource(releaseRootPath), releaseRootPath);
            Release release = null;
            try {
                if (StringUtils.isNotBlank(releaseUuid)) {
                    release = getReleaseManager().findReleaseByUuid(releaseRoot, releaseUuid);
                } else if (StringUtils.isNotBlank(mark)) {
                    release = getReleaseManager().findReleaseByMark(releaseRoot, mark);
                }
            } catch (StagingReleaseManager.ReleaseNotFoundException e) {
                hasRelease = false;
                throw new ReplicationException(Message.error("Release not found"), e);
            }
            if (release == null) {
                hasRelease = false;
                messages.add(Message.warn("No applicable release found for {}", getId()));
                LOG.debug("No applicable release found for {}", getId());
                return null;
            } else {
                hasRelease = true;
            }
            ResourceResolver releaseResolver = getReleaseManager().getResolverForRelease(release, null, false);
            BeanContext context = new BeanContext.Service(releaseResolver);

            PublicationReceiverFacade publisher = createTargetFacade(replicationConfig, context);
            return new ReplicatorStrategy(processedChangedPaths, release, context, replicationConfig, messages, publisher, forceCheck);
        }

        /**
         * Create the facade for the publisher / the backend where the release is replicated to.
         * The {replicationConfig} is actually of type CONFIG, but strangely this leads into trouble with the compiler if
         * we use this type.
         */
        @Nonnull
        protected abstract PublicationReceiverFacade createTargetFacade(@Nonnull AbstractReplicationConfig replicationConfig, @Nonnull BeanContext context);

        @Override
        public abstract String getType();

        @Nullable
        @Override
        public Long getRunStartedAt() {
            return startedAt;
        }

        @Nullable
        @Override
        public Long getRunFinished() {
            return finished;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("id", getId())
                    .append("title", title)
                    .append("state", state)
                    .toString();
        }

        @Override
        public void abort() {
            if (runningStrategy == null) { // reset status.
                state = idle;
            }
            abort(false);
        }

        protected Release getRelease(@Nonnull ResourceResolver resolver) {
            Resource releaseRoot = resolver.getResource(this.releaseRootPath);
            if (releaseRoot == null) { // safety check - strange case. Site removed? Inaccessible?
                LOG.warn("Cannot find release root {}", this.releaseRootPath);
            }
            return releaseRoot != null ? getReleaseManager().findReleaseByMark(releaseRoot, this.mark) : null;
        }

        @Nullable
        @Override
        public Long getLastReplicationTimestamp() {
            UpdateInfo updateInfo = getTargetReleaseInfo();
            return updateInfo != null ? updateInfo.lastReplication : null;
        }

        @Nullable
        @Override
        public Boolean isSynchronized(@Nonnull ResourceResolver resolver) {
            UpdateInfo updateInfo = getTargetReleaseInfo();
            Boolean result = null;
            if (updateInfo != null && isNotBlank(updateInfo.originalPublisherReleaseChangeId)) {
                Release release = getRelease(resolver);
                if (release != null) {
                    result = StringUtils.equals(release.getChangeNumber(), updateInfo.originalPublisherReleaseChangeId);
                }
            }
            return result;
        }

        /**
         * Internal method - use {@link #getTargetReleaseInfo()} since this might be cached by it.
         */
        protected UpdateInfo remoteReleaseInfo() throws ReplicationException {
            if (!isEnabled() || StringUtils.isAllBlank(releaseUuid, mark)) {
                return null;
            }
            UpdateInfo result = null;
            try (ResourceResolver serviceResolver = makeResolver()) {
                LOG.debug("Querying target release info of {}", getId());
                ReplicatorStrategy strategy = makeReplicatorStrategy(serviceResolver, null, false);
                if (strategy != null) {
                    result = strategy.remoteReleaseInfo();
                }
            }
            return result;
        }

        /**
         * The information about the state of the replication on the target system.
         */
        protected UpdateInfo getTargetReleaseInfo() {
            try {
                return remoteReleaseInfo();
            } catch (ReplicationException | RuntimeException e) {
                LOG.error("Error during getTargetReleaseInfo: " + e, e);
                return null;
            }
        }

        @Override
        public void updateSynchronized() {
            // empty
        }

        @Override
        public ReplicationConfig getReplicationConfig() {
            return this.cachedConfig;
        }

        @Nonnull
        protected Resource getHistoryMetaResource(ResourceResolver resolver, String path, boolean createIfNecessary)
                throws ReplicationException {
            String metapath = appendPaths(ReplicationConstants.PATH_METADATA, path) + ReplicationConstants.NODE_METADATA_HISTORY;
            Resource resource = resolver.getResource(metapath);
            if (createIfNecessary && resource == null) {
                try {
                    resource = ResourceUtil.getOrCreateResource(resolver, metapath, TYPE_SLING_FOLDER + "/" + NT_UNSTRUCTURED + "/" + NT_UNSTRUCTURED);
                } catch (RepositoryException e) {
                    throw new ReplicationException(Message.error("Could not create replication history at {}", metapath), e);
                }
                if (resource == null) {
                    throw new ReplicationException(Message.error("Could not create replication history at {}", metapath), null);
                }
            }
            return resource;
        }

        /**
         * Saves timestamps of last run, last successful run, last unsuccessful run, last abort and the errors on the last unsuccessful run.
         */
        protected void updateHistory() {
            LOG.info("Writing history entry for {} state {}", configPath, state);
            try (ResourceResolver serviceResolver = makeResolver()) {
                // we use our own resolver since the resolver used otherwise might be rolled back.
                Resource historyResource = getHistoryMetaResource(serviceResolver, configPath, true);
                ModifiableValueMap vm = historyResource.adaptTo(ModifiableValueMap.class);
                long time = System.currentTimeMillis();
                switch (state) {
                    case success:
                        vm.put(PROP_LAST_SUCCESS_TIME, time);
                        break;
                    case aborted:
                        vm.put(PROP_LAST_ABORT_TIME, time);
                        break;
                    case processing:
                        vm.put(PROP_LAST_RUN_TIME, time);
                        break;
                    case error:
                        vm.put(PROP_LAST_ERROR_TIME, time);
                        Gson gson = new GsonBuilder().create();
                        String msgJson = gson.toJson(getMessages());
                        vm.put(PROP_LAST_ERROR_MESSAGES, StringUtils.defaultString(msgJson));
                        break;
                }
                serviceResolver.commit();
            } catch (ReplicationException e) {
                messages.addAll(e.getMessages());
            } catch (RuntimeException | PersistenceException e) {
                messages.add(Message.error("Error writing history entry for " + configPath), e);
            }
        }

        @Override
        public Map<ReleaseChangeProcessorState, ReplicationHistoryEntry> getHistory() {
            Map<ReleaseChangeProcessorState, ReplicationHistoryEntry> result =
                    new EnumMap<ReleaseChangeProcessorState, ReplicationHistoryEntry>(ReleaseChangeProcessorState.class);
            try (ResourceResolver serviceResolver = makeResolver()) {
                Resource historyResource = getHistoryMetaResource(serviceResolver, configPath, false);
                ValueMap vm = historyResource != null ? historyResource.getValueMap() : null;
                if (vm != null) {
                    Long time = vm.get(PROP_LAST_SUCCESS_TIME, Long.class);
                    if (time != null) {
                        result.put(success, new ReplicationHistoryEntryImpl(success, time, null));
                    }
                    time = vm.get(PROP_LAST_ABORT_TIME, Long.class);
                    if (time != null) {
                        result.put(aborted, new ReplicationHistoryEntryImpl(aborted, time, null));
                    }
                    time = vm.get(PROP_LAST_RUN_TIME, Long.class);
                    if (time != null) {
                        result.put(processing, new ReplicationHistoryEntryImpl(processing, time, null));
                    }
                    time = vm.get(PROP_LAST_ERROR_TIME, Long.class);
                    if (time != null) {
                        String messages = vm.get(PROP_LAST_ERROR_MESSAGES, String.class);
                        result.put(processing, new ReplicationHistoryEntryImpl(processing, time, messages));
                    }
                }
            } catch (ReplicationException e) {
                messages.addAll(e.getMessages());
            } catch (RuntimeException e) {
                messages.add(Message.error("Error writing history entry for " + configPath), e);
            }
            return result;
        }
    }

    protected class ReplicationHistoryEntryImpl implements ReleaseChangeProcess.ReplicationHistoryEntry {

        protected final ReleaseChangeProcess.ReleaseChangeProcessorState historyState;
        protected final Long timestamp;
        protected final MessageContainer historyMessages;

        public ReplicationHistoryEntryImpl(ReleaseChangeProcess.ReleaseChangeProcessorState historyState, Long timestamp, String historyMessagesJson) {
            this.historyState = historyState;
            this.timestamp = timestamp;
            if (StringUtils.isNotBlank(historyMessagesJson)) {
                Gson gson = new GsonBuilder().create();
                this.historyMessages = gson.fromJson(historyMessagesJson, MessageContainer.class);
            } else {
                this.historyMessages = null;
            }
        }

        @Nonnull
        @Override
        public ReleaseChangeProcess.ReleaseChangeProcessorState getState() {
            return historyState;
        }

        @Nonnull
        @Override
        public Long getTimestamp() {
            return timestamp;
        }

        @Nullable
        @Override
        public MessageContainer getMessages() {
            return historyMessages;
        }
    }
}

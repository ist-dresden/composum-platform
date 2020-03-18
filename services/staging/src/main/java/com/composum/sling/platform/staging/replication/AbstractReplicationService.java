package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;
import static com.composum.sling.platform.staging.ReleaseChangeProcess.ReleaseChangeProcessorState.*;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Base class for services that use the {@link ReplicatorStrategy} to replicate staged content.
 * This applies to both in-place replication on the current server, as well as to remote replication.
 */
@Component()
public abstract class AbstractReplicationService<PROCESS extends AbstractReplicationService.AbstractReplicationProcess,
        CONFIG extends AbstractReplicationConfig> implements ReleaseChangeEventListener {

    public static final String PATH_CONFIGROOT = "/conf";
    public static final String DIR_REPLICATION = "/replication";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractReplicationService.class);

    /**
     * Maps the path to the replication configuration to the process.
     */
    protected final Map<String, PROCESS> processesCache = Collections.synchronizedMap(new HashMap<>());

    @Nonnull
    @Override
    public Collection<PROCESS> processesFor(@Nullable StagingReleaseManager.Release release) {
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
        String configparent = PATH_CONFIGROOT + releasePath + DIR_REPLICATION;

        List<CONFIG> configs = new ArrayList<>();
        Resource configroot = releaseRoot.getResourceResolver().getResource(configparent);
        if (configroot != null) {
            for (Resource child : configroot.getChildren()) {
                if (getReplicationType().getServiceId().equals(child.getValueMap().get(ReplicationConfig.PN_REPLICATIN_TYPE))) {
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
            process.readConfig(replicationConfig);
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
    protected ResourceResolver makeResolver() throws LoginException {
        return getResolverFactory().getServiceResourceResolver(null);
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

    protected abstract class AbstractReplicationProcess implements ReleaseChangeProcess {

        protected final Object changedPathsChangeLock = new Object();
        protected volatile MessageContainer messages = new MessageContainer(LoggerFactory.getLogger(getClass()));
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
        @Nonnull
        protected volatile Set<String> changedPaths = new LinkedHashSet<>();
        @Nonnull
        protected volatile String releaseRootPath;
        protected volatile Boolean active;
        protected volatile String releaseUuid;

        protected AbstractReplicationProcess(@Nonnull Resource releaseRoot, @Nonnull CONFIG config) {
            releaseRootPath = releaseRoot.getPath();
            readConfig(config);
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

        protected boolean abort(boolean hard) {
            boolean isNotRunning = true;
            synchronized (changedPathsChangeLock) {
                ReplicatorStrategy runningStrategyCopy = runningStrategy;
                if (runningStrategyCopy != null) {
                    if (hard) {
                        runningStrategy = null;
                    }
                    runningStrategyCopy.setAbortAtNextPossibility();
                    isNotRunning = false;
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
         */
        @Nonnull
        protected Set<String> swapOutChangedPaths() {
            Set<String> processedChangedPaths;
            synchronized (changedPathsChangeLock) {
                processedChangedPaths = changedPaths;
                changedPaths = new LinkedHashSet<>();
            }
            return processedChangedPaths;
        }

        /**
         * Adds unprocessed paths which were taken out of {@link #changedPaths} by {@link #swapOutChangedPaths()}
         * back into the {@link #changedPaths}.
         */
        protected void addBackChangedPaths(Set<String> unProcessedChangedPaths) {
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
                    StagingReleaseManager.Release release = releaseRoot != null ? getReleaseManager().findReleaseByMark(releaseRoot, mark) : null;
                    active = release != null;
                } catch (LoginException e) {
                    LOG.error("Can't get service resolver" + e, e);
                }
            }
            return Boolean.TRUE.equals(active);
        }

        /**
         * Called as often as possible to adapt to config changes.
         */
        protected void readConfig(@Nonnull CONFIG replicationConfig) {
            configPath = requireNonNull(replicationConfig.getPath());
            title = replicationConfig.getTitle();
            description = replicationConfig.getDescription();
            enabled = replicationConfig.isEnabled();
            mark = replicationConfig.getStage();
            active = null;
        }

        public boolean appliesTo(StagingReleaseManager.Release release) {
            ResourceResolver resolver = release.getReleaseRoot().getResourceResolver();
            CONFIG publicationConfig = new BeanContext.Service(resolver).adaptTo(getReplicationConfigClass());
            List<String> marks = release.getMarks();
            return publicationConfig != null && publicationConfig.isEnabled() && (
                    marks.contains(publicationConfig.getStage().toLowerCase())
                            || marks.contains(publicationConfig.getStage().toUpperCase()));
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

                Set<String> processedChangedPaths = swapOutChangedPaths();
                try {
                    strategy = makeReplicatorStrategy(serviceResolver, processedChangedPaths);
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
                } finally {
                    addBackChangedPaths(processedChangedPaths);
                }

            } catch (LoginException e) { // misconfiguration
                messages.add(Message.error("Can't get service resolver"), e);
            } catch (InterruptedException e) {
                LOG.error("Interrupted " + e, e);
                messages.add(Message.warn("Interrupted"), e);
            } catch (ReplicationFailedException | RuntimeException e) {
                messages.add(Message.error("Other error: ", e.toString()), e);
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
            }
        }

        @Override
        @Nullable
        public ReleaseChangeEventPublisher.CompareResult compareTree(@Nonnull ResourceHandle resource,
                                                                     int details) throws ReplicationFailedException {
            if (!isEnabled()) {
                return null;
            }
            ReplicatorStrategy strategy = makeReplicatorStrategy(resource.getResourceResolver(), Collections.singleton(resource.getPath()));
            if (strategy == null) {
                return null;
            }
            try {
                return strategy.compareTree(details);
            } catch (PublicationReceiverFacade.PublicationReceiverFacadeException e) {
                throw new ReplicationFailedException(e.getMessage(), e, null);
            }
        }

        @Nullable
        protected ReplicatorStrategy makeReplicatorStrategy(ResourceResolver serviceResolver, Set<String> processedChangedPaths) {
            Resource configResource = serviceResolver.getResource(configPath);
            CONFIG replicationConfig = configResource != null ?
                    new BeanContext.Service(serviceResolver).withResource(configResource)
                            .adaptTo(getReplicationConfigClass()) : null;
            if (replicationConfig == null || !replicationConfig.isEnabled()) {
                LOG.warn("Disabled / unreadable config, not run: {}", getId());
                return null; // warning - should normally have been caught before
            }

            Resource releaseRoot = requireNonNull(serviceResolver.getResource(releaseRootPath), releaseRootPath);
            StagingReleaseManager.Release release = null;
            if (StringUtils.isNotBlank(releaseUuid)) {
                release = getReleaseManager().findReleaseByUuid(releaseRoot, releaseUuid);
            } else if (StringUtils.isNotBlank(mark)) {
                release = getReleaseManager().findReleaseByMark(releaseRoot, mark);
            }
            if (release == null) {
                LOG.warn("No applicable release found for {}", getId());
                return null;
            }
            ResourceResolver releaseResolver = getReleaseManager().getResolverForRelease(release, null, false);
            BeanContext.Service context = new BeanContext.Service(releaseResolver);

            PublicationReceiverFacade publisher = createTargetFacade(replicationConfig, context);
            return new ReplicatorStrategy(processedChangedPaths, release, context, replicationConfig, messages, publisher);
        }

        /**
         * Create the facade for the publisher / the backend where the release is replicated to.
         */
        @Nonnull
        protected abstract PublicationReceiverFacade createTargetFacade(@Nonnull CONFIG replicationConfig, @Nonnull BeanContext.Service context);

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

        protected StagingReleaseManager.Release getRelease(@Nonnull ResourceResolver resolver) {
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
                StagingReleaseManager.Release release = getRelease(resolver);
                if (release != null) {
                    result = StringUtils.equals(release.getChangeNumber(), updateInfo.originalPublisherReleaseChangeId);
                }
            }
            return result;
        }

        /**
         * Internal method - use {@link #getTargetReleaseInfo()} since this might be cached by it.
         */
        protected UpdateInfo remoteReleaseInfo() throws PublicationReceiverFacade.PublicationReceiverFacadeException {
            if (!isEnabled()) {
                return null;
            }
            UpdateInfo result = null;
            try (ResourceResolver serviceResolver = makeResolver()) {
                LOG.info("Querying target release info of {}", getId());
                ReplicatorStrategy strategy = makeReplicatorStrategy(serviceResolver, null);
                if (strategy != null) {
                    result = strategy.remoteReleaseInfo();
                }
            } catch (LoginException e) { // serious misconfiguration
                LOG.error("Can't get service resolver: " + e, e);
                // ignore - that'll reappear when publishing and treated there
            }
            return result;
        }

        /**
         * The information about the state of the replication on the target system.
         */
        protected UpdateInfo getTargetReleaseInfo() {
            try {
                return remoteReleaseInfo();
            } catch (PublicationReceiverFacade.PublicationReceiverFacadeException e) {
                LOG.error("" + e, e);
                return null;
            }
        }

        @Override
        public void updateSynchronized() {
            // empty
        }
    }
}

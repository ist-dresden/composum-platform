package com.composum.sling.platform.staging.model;

import com.composum.platform.commons.util.ExceptionThrowingFunction;
import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.Restricted;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.util.I18N;
import com.composum.sling.core.util.RequestUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.Release;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.AggregatedReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.ReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.composum.sling.platform.staging.StagingConstants.CURRENT_RELEASE;
import static com.composum.sling.platform.staging.StagingConstants.TIMESTAMP_FORMAT;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.staging.impl.PlatformStagingServlet.SERVICE_KEY;

@Restricted(key = SERVICE_KEY)
public class ReplicationStatus extends AbstractSlingBean {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationStatus.class);

    public enum State {
        /**
         * Needs synchronization.
         */
        undefined,
        synchron,
        /**
         * In the queue or actually running.
         */
        running,
        /**
         * Internal error or no connection possible.
         */
        faulty,
        /**
         * No release has the mark for this synchronization.
         */
        norelease,
        /**
         * Switched off in the configuration.
         */
        switchedoff
    }

    public class ReplicationProcessState implements Comparable<ReplicationProcessState> {

        protected final String key;
        protected final ReplicationStateInfo state;

        public ReplicationProcessState(String key, ReplicationStateInfo info) {
            this.key = key;
            this.state = info;
        }

        public String getId() {
            return StringUtils.substringAfterLast(state.id, "/");
        }

        public String getTitle() {
            String value;
            return StringUtils.isNotBlank(state.name)
                    ? state.name : StringUtils.isNotBlank(value = getSourcePath())
                    ? value : getId();
        }

        public String getType() {
            return state.type;
        }

        public String getSourcePath() {
            return null; // FIXME 'sourcePath' attribute
        }

        public State getState() {
            if (isRunning()) {
                return State.running;
            } else if (!isEnabled()) {
                return State.switchedoff;
            } else if (isFaulty()) {
                return State.faulty;
            } else if (isSynchronized()) {
                return State.synchron;
            } else if (!hasRelease()) {
                return State.norelease;
            }
            return State.undefined;
        }

        public boolean hasRelease() {
            return state.hasRelease && state.state != ReleaseChangeProcess.ReleaseChangeProcessorState.norelease;
        }

        public boolean isEnabled() {
            return state.enabled;
        }

        public boolean isSynchronized() {
            return Boolean.TRUE.equals(state.isSynchronized);
        }

        public boolean isRunning() {
            return state.state == ReleaseChangeProcess.ReleaseChangeProcessorState.processing
                    || state.state == ReleaseChangeProcess.ReleaseChangeProcessorState.awaiting;
        }

        public boolean isFaulty() {
            return state.state == ReleaseChangeProcess.ReleaseChangeProcessorState.error;
        }

        public String getStartedAt() {
            return getTimestamp(state.startedAt);
        }

        public String getFinishedAt() {
            return getTimestamp(state.finishedAt);
        }

        public String getLastReplication() {
            return getTimestamp(state.lastReplicationTimestamp);
        }

        public int getProgress() {
            return isSynchronized() ? 100 : state.completionPercentage;
        }

        public List<Message> getMessages() {
            return state.messages != null ? state.messages.getMessages() : Collections.emptyList();
        }

        @NotNull
        protected String getTimestamp(@Nullable Long time) {
            return time != null ? new SimpleDateFormat(TIMESTAMP_FORMAT).format(time) : "";
        }

        @NotNull
        public String getJson() {
            StringWriter buffer = new StringWriter();
            try (JsonWriter writer = new JsonWriter(buffer)) {
                toJson(writer);
                writer.flush();
            } catch (IOException ex) {
                LOG.error(ex.getMessage(), ex);
            }
            return Base64.encodeBase64String(buffer.toString().getBytes(StandardCharsets.UTF_8));
        }

        public void toJson(JsonWriter writer) throws IOException {
            writer.beginObject();
            writer.name("id").value(getId());
            writer.name("title").value(getTitle());
            writer.name("sourcePath").value(getSourcePath());
            writer.name("state").value(getState().name());
            writer.name("enabled").value(isEnabled());
            writer.name("synchronized").value(isSynchronized());
            writer.name("running").value(isRunning());
            writer.name("faulty").value(isFaulty());
            writer.name("lastReplication").value(getLastReplication());
            writer.name("startedAt").value(getStartedAt());
            writer.name("finishedAt").value(getFinishedAt());
            writer.name("progress").value(getProgress());
            writer.endObject();
        }

        protected String getComparationKey() {
            return (isEnabled() ? "a_" : "z_") + getTitle();
        }

        @Override
        public int compareTo(@NotNull ReplicationStatus.ReplicationProcessState other) {
            return getComparationKey().compareTo(other.getComparationKey());
        }
    }

    public class ReplicationState {

        protected final AggregatedReplicationStateInfo state;

        private transient Integer progress;

        public ReplicationState(AggregatedReplicationStateInfo state) {
            this.state = state;
        }

        public State getState() {
            if (isRunning()) {
                return State.running;
            } else if (isFaulty()) {
                return State.faulty;
            } else if (isSynchronized()) {
                return State.synchron;
            } else if (!hasRelease()) {
                return State.norelease;
            }
            return State.undefined;
        }

        public boolean isSynchronized() {
            return state.everythingIsSynchronized;
        }

        public boolean hasRelease() {
            return state.allEnabledHaveReleases;
        }

        public boolean isRunning() {
            return state.replicationsAreRunning;
        }

        public boolean isFaulty() {
            return state.haveErrors;
        }

        public int getProgress() {
            if (progress == null) {
                if (isSynchronized() && !isRunning()) {
                    progress = 100;
                } else {
                    progress = 0;
                    int count = 0;
                    List<ReplicationProcessState> processes = getReplicationProcessState();
                    Collections.sort(processes);
                    for (ReplicationProcessState process : processes) {
                        if (process.isEnabled()) {
                            progress += process.getProgress();
                            count++;
                        }
                    }
                    if (count > 0) {
                        progress /= count;
                    }
                }
            }
            return progress;
        }

        @NotNull
        public String getJson() {
            return getJson(this::toJson);
        }

        public Void toJson(JsonWriter writer) throws IOException {
            writer.beginObject();
            writer.name("summary");
            summary(writer);
            writer.name("processes").beginArray();
            for (ReplicationProcessState process : getReplicationProcessState()) {
                process.toJson(writer);
            }
            writer.endArray();
            writer.endObject();
            return null;
        }

        @NotNull
        public String getJsonSummary() {
            return getJson(this::toJsonSummary);
        }

        public Void toJsonSummary(JsonWriter writer) throws IOException {
            summary(writer);
            return null;
        }

        protected void summary(JsonWriter writer) throws IOException {
            writer.beginObject();
            writer.name("stage").value(getStage());
            writer.name("state").value(getState().name());
            writer.name("synchronized").value(isSynchronized());
            writer.name("running").value(isRunning());
            writer.name("faulty").value(isFaulty());
            writer.name("progress").value(getProgress());
            writer.endObject();
        }

        protected String getJson(ExceptionThrowingFunction<JsonWriter, Void, IOException> toJson) {
            StringWriter buffer = new StringWriter();
            try (JsonWriter writer = new JsonWriter(buffer)) {
                toJson.apply(writer);
                writer.flush();
            } catch (IOException ex) {
                LOG.error(ex.getMessage(), ex);
            }
            return Base64.encodeBase64String(buffer.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public class ReleaseModel {

        @Nullable
        protected final Release release;

        public ReleaseModel(@Nullable final Release release) {
            this.release = release;
        }

        public boolean isValid() {
            return release != null;
        }

        public boolean isCurrent() {
            return CURRENT_RELEASE.equals(getKey());
        }

        public String getPath() {
            return release != null ? release.getPath() : "";
        }

        public String getKey() {
            return release != null ? release.getNumber() : "";
        }

        public String getTitle() {
            return getMetaValue(ResourceUtil.JCR_TITLE, getKey());
        }

        public String getTitleString() {
            String title = getMetaValue(ResourceUtil.JCR_TITLE, "");
            return StringUtils.isNotBlank(title) ? title :
                    isCurrent() ? I18N.get(context.getRequest(), "the open next release") : "-- --";
        }

        public String getDescription() {
            return getMetaValue(ResourceUtil.JCR_DESCRIPTION, "");
        }

        public Calendar getCreationDate() {
            return getMetaValue(JcrConstants.JCR_CREATED, null);
        }

        public String getCreationDateString() {
            Calendar timestamp = getCreationDate();
            if (timestamp != null) {
                return new SimpleDateFormat(TIMESTAMP_FORMAT).format(timestamp.getTime());
            } else {
                return "";
            }
        }

        protected <T> T getMetaValue(String name, T defaultValue) {
            Resource metaData = release != null ? release.getMetaDataNode() : null;
            return metaData != null ? metaData.getValueMap().get(name, defaultValue) : null;
        }
    }

    private transient String stage;
    private transient ReleaseModel release;
    private transient ReplicationState replicationState;
    private transient List<ReplicationProcessState> replicationProcessStates;

    private transient StagingReleaseManager releaseManager;
    private transient ReleaseChangeEventPublisher releasePublisher;

    @Override
    public void initialize(@NotNull BeanContext context, @NotNull Resource resource) {
        super.initialize(context, getReleaseRoot(resource));
    }

    protected Resource getReleaseRoot(Resource resource) {
        Resource releaseRoot = resource;
        while (releaseRoot != null && !releaseRoot.isResourceType(TYPE_MIX_RELEASE_ROOT)) {
            releaseRoot = releaseRoot.getParent();
        }
        return releaseRoot != null ? releaseRoot : resource;
    }

    public void toJson() {
        try {
            getReplicationState().toJson(new JsonWriter(getResponse().getWriter()));
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    public void toJsonSummary() {
        try {
            getReplicationState().toJsonSummary(new JsonWriter(getResponse().getWriter()));
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    public String getStage() {
        if (stage == null) {
            stage = RequestUtil.checkSelector(getRequest(), "preview") ? "preview" : "public";
        }
        return stage;
    }

    public ReplicationState getReplicationState() {
        if (replicationState == null) {
            replicationState = new ReplicationState(getReleasePublisher()
                    .aggregatedReplicationState(resource, getStage()));
        }
        return replicationState;
    }

    public List<ReplicationProcessState> getReplicationProcessState() {
        if (replicationProcessStates == null) {
            replicationProcessStates = new ArrayList<>();
            Map<String, ReplicationStateInfo> infoSet = getReleasePublisher().replicationState(resource, getStage());
            for (Map.Entry<String, ReplicationStateInfo> entry : infoSet.entrySet()) {
                replicationProcessStates.add(new ReplicationProcessState(entry.getKey(), entry.getValue()));
            }
        }
        return replicationProcessStates;
    }

    @Nullable
    public ReleaseModel getRelease() {
        if (release == null) {
            ResourceHandle resource = getResource();
            String stage = getStage();
            try {
                release = new ReleaseModel(getReleaseManager().findReleaseByMark(resource, stage));
            } catch (StagingReleaseManager.ReleaseRootNotFoundException e) {
                LOG.warn("Cannot find release root for stage {} of {}", stage, SlingResourceUtil.getPath(resource));
            }
        }
        return release;
    }

    protected StagingReleaseManager getReleaseManager() {
        if (releaseManager == null) {
            releaseManager = Objects.requireNonNull(context.getService(StagingReleaseManager.class));
        }
        return releaseManager;
    }

    protected ReleaseChangeEventPublisher getReleasePublisher() {
        if (releasePublisher == null) {
            releasePublisher = Objects.requireNonNull(context.getService(ReleaseChangeEventPublisher.class));
        }
        return releasePublisher;
    }
}

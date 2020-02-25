package com.composum.sling.platform.staging.replication.model;

import com.composum.platform.commons.util.ExceptionThrowingFunction;
import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.logging.Message;
import com.composum.sling.core.util.RequestUtil;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.AggregatedReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.ReplicationStateInfo;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;

public class ReplicationStatus extends AbstractSlingBean {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationStatus.class);

    public static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";

    public enum State {undefined, synchron, running, faulty, switchedoff}

    private transient ReleaseChangeEventPublisher releasePublisher;

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

        public String getSourcePath() {
            return null; // FIXME 'sourcePath' attribute
        }

        public State getState() {
            return isRunning() ? State.running : isSynchronized() ? State.synchron
                    : isEnabled() ? (isFaulty() ? State.faulty : State.undefined) : State.switchedoff;
        }

        public boolean isEnabled() {
            return state.enabled;
        }

        public boolean isSynchronized() {
            return state.isSynchronized != null && state.isSynchronized;
        }

        public boolean isRunning() {
            return false; // FIXME 'running' state
        }

        public boolean isFaulty() {
            return false; // FIXME 'faulty' state
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

        @Nonnull
        protected String getTimestamp(@Nullable Long time) {
            return time != null ? new SimpleDateFormat(TIMESTAMP_FORMAT).format(time) : "";
        }

        @Nonnull
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
        public int compareTo(@Nonnull ReplicationStatus.ReplicationProcessState other) {
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
            return isRunning() ? State.running : isSynchronized()
                    ? State.synchron : isFaulty() ? State.faulty : State.undefined;
        }

        public boolean isSynchronized() {
            return state.everythingIsSynchronized;
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

        @Nonnull
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

        @Nonnull
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

    private transient String stage;
    private transient ReplicationState replicationState;
    private transient List<ReplicationProcessState> replicationProcessStates;

    @Override
    public void initialize(@Nonnull BeanContext context, @Nonnull Resource resource) {
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
                    .aggregatedReplicationState(resource/*, FIXME stage scope */));
        }
        return replicationState;
    }

    public List<ReplicationProcessState> getReplicationProcessState() {
        if (replicationProcessStates == null) {
            replicationProcessStates = new ArrayList<>();
            Map<String, ReplicationStateInfo> infoSet = getReleasePublisher().replicationState(resource/*, FIXME stage scope */);
            for (Map.Entry<String, ReplicationStateInfo> entry : infoSet.entrySet()) {
                replicationProcessStates.add(new ReplicationProcessState(entry.getKey(), entry.getValue()));
            }
        }
        return replicationProcessStates;
    }

    protected ReleaseChangeEventPublisher getReleasePublisher() {
        if (releasePublisher == null) {
            releasePublisher = Objects.requireNonNull(context.getService(ReleaseChangeEventPublisher.class));
        }
        return releasePublisher;
    }
}

package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.servlet.Status;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

/**
 * A {@link ReplicationException} that occurred on a remote system - e.g. the publisher host.
 */
public class RemoteReplicationException extends ReplicationException {

    @Nullable
    protected Status status;
    @Nullable
    protected Integer statusCode;
    @Nullable
    protected String reasonPhrase;

    /**
     * The {@link Status} object returned from the remote system.
     */
    @Nullable
    public Status getStatus() {
        return status;
    }

    /**
     * The HTTP status code returned from the remote system.
     */
    @Nullable
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * The HTTP reason for the status code returned from the remote system.
     */
    @Nullable
    public String getReasonPhrase() {
        return reasonPhrase;
    }

    @Override
    protected void extendToString(StringBuilder sb) {
        if (statusCode != null) {
            sb.append(", statusCode=").append(statusCode);
        }
        if (StringUtils.isNotBlank(reasonPhrase)) {
            sb.append(", reasonPhrase='").append(reasonPhrase).append('\'');
        }
        if (status != null) {
            sb.append("status=").append(status);
        }
    }

}


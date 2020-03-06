package com.composum.sling.platform.staging.replication;

import java.util.Date;

/**
 * Class with general information about the running update.
 */
public class UpdateInfo {

    /**
     * The update id for the pending operation.
     */
    public String updateId;

    /**
     * The original status of the publishers release.
     */
    public String originalPublisherReleaseChangeId;

    /**
     * The timestamp {@link System#currentTimeMillis()} of the last replication.
     */
    public Long lastReplication;

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("UpdateInfo{");
        sb.append("updateId='").append(updateId).append('\'');
        if (lastReplication != null) {
            sb.append(", lastReplication='").append(new Date(lastReplication)).append('\'');
        }
        if (originalPublisherReleaseChangeId != null) {
            sb.append(", relchgid='").append(originalPublisherReleaseChangeId).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}

package com.composum.sling.platform.staging.replication;

import javax.annotation.Nullable;

/** The configuration of a replication. */
public interface ReplicationConfiguration {

    /**
     * If the replication requests moving the replicated site to another path, this returns the original path
     * - normally the site root. E.g. /content/tenant/site
     */
    @Nullable
    String getMoveSource();

    /**
     * If the replication requests moving the replicated site to another path, this returns the destination path
     * corresponding to the {@link #getMoveDestination()}. E.g. /public/tenant/site
     *
     */
    @Nullable
    String getMoveDestination();

}

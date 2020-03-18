package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.platform.staging.replication.AbstractReplicationConfig;
import com.composum.sling.platform.staging.replication.ReplicationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

/**
 * Bean modeling a inplace publication configuration for use with the replication mechanism. The resource is a subnode below /conf/{sitepath}/{site}/replication/ .
 */
public class InPlaceReplicationConfig extends AbstractReplicationConfig {
    private static final Logger LOG = LoggerFactory.getLogger(InPlaceReplicationConfig.class);

    protected static final ReplicationType INPLACE_REPLICATION_TYPE = new InplaceReplicationType();

    @Nonnull
    @Override
    public ReplicationType getReplicationType() {
        return INPLACE_REPLICATION_TYPE;
    }
}

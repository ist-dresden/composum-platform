package com.composum.sling.platform.staging.replication.postprocess;

import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;

/**
 * A plugin for the replication that can change a versionable after copying it to the destination system into a
 * temporary directory. For instance adapt references.
 */
public interface VersionablePostProcessor {

    /**
     * Can change the copied resource according to the configuration.
     *
     * @param resource      a copy of the versionable that is to be replicated.
     * @param configuration the configuration for the replication.
     */
    void postprocess(@Nonnull Resource resource, @Nonnull ReplicationConfiguration configuration);

}

package com.composum.sling.platform.staging.replication.inplace;

import com.composum.sling.platform.staging.replication.ReplicationType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class InplaceReplicationType implements ReplicationType {

    public static final String SERVICE_ID = "inplace";

    @NotNull
    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    @NotNull
    @Override
    public String getTitle() {
        return "In-Place";
    }

    @Nullable
    @Override
    public String getDescription() {
        return "the 'on system' in place replication";
    }

    @NotNull
    @Override
    public String getResourceType() {
        return "composum/platform/replication/inplace";
    }
}

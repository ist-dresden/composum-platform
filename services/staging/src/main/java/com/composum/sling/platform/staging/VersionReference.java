package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Calendar;

/** Provides access to the data saved about the activation in a version reference within a release (the {@link StagingConstants#TYPE_VERSIONREFERENCE} nodes. */
public interface VersionReference {

    @Nonnull
    ReleasedVersionable getReleasedVersionable();

    boolean isActive();

    @Nullable
    Calendar getLastActivated();

    @Nullable
    String getLastActivatedBy();

    @Nullable
    Calendar getLastDeactivated();

    @Nullable
    String getLastDeactivatedBy();

    /** Returns the nt:version resource for the activated version within the /jcr:system/jcr:versionStorage. Use with caution. */
    @Nullable
    Resource getVersionResource();

    /** The date when the version was checked in. */
    @Nullable
    Calendar getVersionCreated();

    @Nonnull
    Release getRelease();

    /** The absolute path for the versionable, as it appears in the release. */
    @Nonnull
    String getPath();

    /** The {@value com.composum.sling.core.util.ResourceUtil#PROP_PRIMARY_TYPE} of the resource. */
    @Nonnull
    String getType();
}

package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A hook into the {@link DefaultStagingReleaseManager}.
 */
public interface StagingReleaseManagerPlugin {

    /**
     * Is called by the {@link DefaultStagingReleaseManager} after changing a release tree.
     * Since deactivated versionables can sometimes create an inconsistent state of the release work tree, this offers
     * a possibility to customize the release work tree to make it consistent. For example in Composum Pages,
     * the pages consist of a cpp:Page node (which can have subpages) with a mandatory cpp:PageContent, which is versionable
     * and can be deactivated, and thus vanish. This needs a workaround.
     *
     * @param release      the release this applies to
     * @param changedPaths a list of paths to {@link StagingConstants#TYPE_VERSIONREFERENCE} within the releases worktree copy for which there have been changes
     */
    void fixupReleaseForChanges(@Nonnull StagingReleaseManager.Release release, @Nonnull List<String> changedPaths);

}

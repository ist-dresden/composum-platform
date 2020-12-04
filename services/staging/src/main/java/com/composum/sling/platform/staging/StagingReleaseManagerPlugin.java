package com.composum.sling.platform.staging;

import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.util.Set;

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
     * @param release           the release this applies to
     * @param workspaceCopyNode the root of the workspace copy of the release
     * @param changedPaths      a list of paths to {@link StagingConstants#TYPE_VERSIONREFERENCE} within the releases worktree copy for which there have been changes
     * @param event             the release change event that is going to be sent - might get updated by the plugin if it changes things
     */
    void fixupReleaseForChanges(@Nonnull Release release, Resource workspaceCopyNode, @Nonnull Set<String> changedPaths, ReleaseChangeEvent event) throws RepositoryException;

}

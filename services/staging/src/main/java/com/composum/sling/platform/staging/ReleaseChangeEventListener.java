package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static com.composum.sling.core.util.SlingResourceUtil.isSameOrDescendant;

/**
 * <p>
 * A service that receives activation events and can publish these - e.g. to /publish and /preview in the JCR or even remote servers.
 * There can be an arbitrary number of {@link ReleaseChangeEventListener}s, which decide based on the {@link ReleaseChangeEvent}s they receive
 * whether they have to do something.
 * </p>
 * <p>
 * The transmitted resources can be {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s, hierarchy nodes containing several
 * {@value com.composum.sling.core.util.CoreConstants#MIX_VERSIONABLE}s or none (e.g. assets). The {@link ReleaseChangeEventListener}
 * has to check that on his own, if that's relevant.
 * Usually it will be Resources from a {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}
 * for the given release.
 * </p>
 * <p>
 * It's the {@link ReleaseChangeEventListener}'s job to decide what actions to take on that. It can assume that it receives all
 * {@link ReleaseChangeEvent}s or that the user is informed about an error (or, at least, did not get an acknowledgement of success)
 * and that it is the users responsibility to trigger a full site update to fix any errors due to missing events.
 * </p>
 * <p>
 * For a {@link ReleaseChangeEventListener} it is advisable to check whether resources referred by the activated / updated resources are updated, too,
 * since e.g. for assets and configurations there might not be any activation events. Also the order of children in the parent nodes of a resource
 * might have changed.
 * </p>
 */
public interface ReleaseChangeEventListener {

    /**
     * This informs the replication service about an activation / deactivation / update. The publisher can decide on his own
     * whether he is responsible. The processing should be synchronous, so that the user can be notified whether it succeeded or not.
     * CAUTION: the changes can also encompass the attributes and node order of parent nodes of the resources transmitted in the event.
     */
    default void receive(ReleaseChangeEvent releaseChangeEvent) throws ReleaseChangeFailedException {
        // default empty - if processesFor contains the things to do
    }

    /**
     * Collection of {@link ReleaseChangeProcess}es that should be run in background upon receiving events for the release.
     * This is usually an alternative to {@link #receive(ReleaseChangeEvent)} for things that can take more time
     * than a request should take. If this returns some processes, they are {@link ReleaseChangeProcess#triggerProcessing(ReleaseChangeEvent)}
     * with the events and then {@link ReleaseChangeProcess#run()}.
     */
    @Nonnull
    default Collection<? extends ReleaseChangeProcess> processesFor(@Nonnull Release release) {
        return Collections.emptyList();
    }

    /**
     * Collection of {@link ReleaseChangeProcess}es for any of the releases at the release root containing resource.
     */
    @Nonnull
    default Collection<? extends ReleaseChangeProcess> processesFor(@Nullable Resource resource) {
        return Collections.emptyList();
    }


}

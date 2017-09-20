package com.composum.platform.models.annotations;

import org.apache.sling.api.resource.Resource;

/**
 * Strategy to determine the resource the properties of the model should be initialized from.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public interface DetermineResourceStategy {

    /** Determines the resource the properties of the model should be initialized from. */
    Resource determineResource(Resource requestResource);

    /** Default: just returns the original resource unchanged. */
    class OriginalResourceStrategy implements DetermineResourceStategy {
        @Override
        public Resource determineResource(Resource requestResource) {
            return requestResource;
        }
    }

}

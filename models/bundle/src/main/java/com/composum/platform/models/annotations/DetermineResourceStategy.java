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

    /**
     * Signifies the use of a default {@link DetermineResourceStategy} - for use as "no value" in annotations. Don't use
     * this otherwise; calling it throws exceptions.
     */
    class DefaultDetermineResourceStrategy implements DetermineResourceStategy {
        /** Throws an exeption - don't use it. */
        @Override
        public Resource determineResource(Resource requestResource) {
            throw new UnsupportedOperationException("Bug: this should never be actually called.");
        }
    }
}

package com.composum.platform.models.annotations;

import com.composum.sling.core.BeanContext;
import org.apache.sling.api.resource.Resource;

/**
 * Strategy to determine the resource the properties of the model should be initialized from.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public interface DetermineResourceStategy {

    /**
     * Determines the resource the properties of the model should be initialized from.
     *
     * @param beanContext     used to look up services, if neccesary.
     * @param requestResource the resource for which we determine the resource, nullable
     * @return nullable
     */
    Resource determineResource(BeanContext beanContext, Resource requestResource);

    /** Default: just returns the original resource unchanged. */
    class OriginalResourceStrategy implements DetermineResourceStategy {
        @Override
        public Resource determineResource(BeanContext beanContext, Resource requestResource) {
            return requestResource;
        }
    }

}

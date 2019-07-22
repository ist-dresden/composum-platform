package com.composum.sling.platform.staging.query;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import java.util.Map;

/**
 * Encapsulates the result of a query for specific properties and pseudo-properties. This contains only the queried
 * subset of properties of the queried resource. The {@link Map} functionality gives the entries with their default Java
 * types.
 *
 * @see Query#selectAndExecute(String...)
 */
public interface QueryValueMap extends Map<String, Object>, ValueMap {

    /** Returns the found resource the values are from. Could be null in case of right outer join. */
    Resource getResource();

    /** In case of a join, returns the found resource for the given join selector. */
    Resource getJoinResource(String selector);

}

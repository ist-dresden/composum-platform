package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.*;

/**
 * Helper to copy / update a resource tree into another resource tree while some attributes are omitted and some nodes can be transformed.
 */
class NodeTreeSynchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(NodeTreeSynchronizer.class);

    void update(Resource from, Resource to) throws RepositoryException {
        updateAttributes(ResourceHandle.use(from), ResourceHandle.use(to));
    }

    protected void updateAttributes(ResourceHandle from, ResourceHandle to) throws RepositoryException {
        ValueMap fromAttributes = ResourceUtil.getValueMap(from);
        ModifiableValueMap toAttributes = to.adaptTo(ModifiableValueMap.class);
        // first copy type information since this changes attributes
        toAttributes.put(ResourceUtil.PROP_PRIMARY_TYPE, fromAttributes.get(ResourceUtil.PROP_PRIMARY_TYPE, null));
        toAttributes.put(ResourceUtil.PROP_MIXINTYPES, fromAttributes.get(ResourceUtil.PROP_MIXINTYPES, new String[0]));

        for (Map.Entry<String, Object> entry : fromAttributes.entrySet()) {
            if (!ignoreAttribute(from, entry.getKey(), true)) {
                try {
                    toAttributes.put(entry.getKey(), entry.getValue());
                } catch (IllegalArgumentException e) { // probably extend protectedMetadataAttributes
                    LOG.warn("Couldn't copy probably protected attribute {} - {}", entry.getKey(), e.toString());
                }
            }
        }

        for (String key : CollectionUtils.subtract(toAttributes.keySet(), fromAttributes.keySet())) {
            if (!fromAttributes.containsKey(key) && !ignoreAttribute(from, key, false)) {
                try {
                    toAttributes.remove(key);
                } catch (UnsupportedOperationException e) {
                    // shouldn't be possible - how come that isn't there on source?
                    LOG.warn("Couldn't copy remove protected attribute {} - {}", key, e.toString());
                }
            }
        }
    }

    /**
     * (Incomplete) enumeration of protected attributes which shouldn't be copied.
     * TODO either extend or handle differently.
     */
    protected static final Collection<String> protectedMetadataAttributes =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("jcr:uuid", "jcr:lastModified",
                    "jcr:lastModifiedBy", "jcr:created", "jcr:createdBy", "jcr:isCheckedOut", "jcr:baseVersion",
                    "jcr:versionHistory", "jcr:predecessors", "jcr:mergeFailed", "jcr:configuration")));

    /**
     * Specifies if the attribute is ignored in the synchronization process. In the default implementation we exclude
     * some protected attributes from the source.
     *
     * @param resource      the resource that has the attribute
     * @param attributename the name of the attribute
     * @param source        true if that's the source, false if that's the destination
     * @return true iff the attribute should be copied.
     */
    protected boolean ignoreAttribute(ResourceHandle resource, String attributename, boolean source) {
        return source && protectedMetadataAttributes.contains(attributename);
    }

}

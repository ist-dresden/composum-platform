package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.ResourceUtil.PROP_MIXINTYPES;
import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;

/**
 * Helper to copy / update a resource tree into another resource tree while some attributes are omitted and some nodes can be transformed.
 */
public class NodeTreeSynchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(NodeTreeSynchronizer.class);

    /**
     * Updates the resource tree below {to} to match the resource tree below {from}, creating missing nodes, removing superfluous nodes, setting / removing attributes.
     * Protected attributes are ignored (e.g. jcr:uuid can't be set) - see {@link #ignoreAttribute(ResourceHandle, String, boolean)}.
     *
     * @param from the source
     * @param to   the destination which we update
     * @throws RepositoryException if we couldn't finish the operation
     * @see #ignoreAttribute(ResourceHandle, String, boolean)
     */
    public void update(@Nonnull Resource from, @Nonnull Resource to) throws RepositoryException, PersistenceException {
        updateAttributes(ResourceHandle.use(from), ResourceHandle.use(to));
        updateChildren(from, to);
    }

    /**
     * Updates all attributes of {to} from their values at {from} - including primary type and mixins.
     * Protected attributes are ignored (e.g. jcr:uuid can't be set) - see {@link #ignoreAttribute(ResourceHandle, String, boolean)}.
     *
     * @param from the source
     * @param to   the destination which we update
     * @throws RepositoryException if we couldn't finish the operation
     * @see #ignoreAttribute(ResourceHandle, String, boolean)
     */
    public void updateAttributes(ResourceHandle from, ResourceHandle to) {
        ValueMap fromAttributes = ResourceUtil.getValueMap(from);
        ModifiableValueMap toAttributes = to.adaptTo(ModifiableValueMap.class);
        // first copy type information since this changes attributes
        toAttributes.put(PROP_PRIMARY_TYPE, fromAttributes.get(PROP_PRIMARY_TYPE));
        toAttributes.put(PROP_MIXINTYPES, fromAttributes.get(PROP_MIXINTYPES, new String[0]));

        for (Map.Entry<String, Object> entry : fromAttributes.entrySet()) {
            if (!ignoreAttribute(from, entry.getKey(), true)) {
                try {
                    toAttributes.put(entry.getKey(), entry.getValue());
                } catch (IllegalArgumentException e) { // probably extend protectedMetadataAttributes
                    LOG.info("Could not copy probably protected attribute {} - {}", entry.getKey(), e.toString());
                }
            }
        }

        for (String key : CollectionUtils.subtract(toAttributes.keySet(), fromAttributes.keySet())) {
            if (!fromAttributes.containsKey(key) && !ignoreAttribute(from, key, false) &&
                    !PROP_MIXINTYPES.equals(key)) {
                try {
                    toAttributes.remove(key);
                } catch (IllegalArgumentException e) {
                    // shouldn't be possible - how come that isn't there on source?
                    LOG.error("Couldn't copy remove protected attribute {} - {}", key, e.toString());
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

    /**
     * Creates / Updates / Deletes the children of {to} according to {from}. The attributes of {from} are ignored,
     * the attributes of the children are handled.
     */
    protected void updateChildren(Resource from, Resource to) throws RepositoryException, PersistenceException {
        List<Resource> fromChildren = IteratorUtils.toList(from.listChildren());
        List<String> fromChildrenNames = fromChildren.stream().map(Resource::getName).collect(Collectors.toList());
        for (Resource child : to.getChildren()) {
            if (!fromChildrenNames.contains(child.getName()))
                child.getResourceResolver().delete(child);
        }
        List<String> toChildrenNames = IteratorUtils.toList(to.listChildren())
                .stream().map(Resource::getName).collect(Collectors.toList());
        for (Resource fromchild : from.getChildren()) {
            if (!toChildrenNames.contains(fromchild.getName())) {
                Resource tochild = to.getResourceResolver().create(to, fromchild.getName(), null);
                update(fromchild, tochild);
            }
        }
    }

}

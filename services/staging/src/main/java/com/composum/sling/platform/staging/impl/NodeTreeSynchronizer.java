package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.*;
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
        updateSubtree(ResourceHandle.use(from), ResourceHandle.use(to));
    }

    /**
     * Internal entry point: updates the resource tree below {to} to match the resource tree below {from},
     * creating missing nodes, removing superfluous nodes, setting / removing attributes.
     * Protected attributes are ignored (e.g. jcr:uuid can't be set) - see {@link #ignoreAttribute(ResourceHandle, String, boolean)}.
     *
     * @param from the source
     * @param to   the destination which we update
     * @throws RepositoryException if we couldn't finish the operation
     * @see #ignoreAttribute(ResourceHandle, String, boolean)
     */
    protected void updateSubtree(@Nonnull ResourceHandle from, @Nonnull ResourceHandle to) throws RepositoryException, PersistenceException {
        updateAttributes(from, to);
        updateChildren(from, to);
    }

    /**
     * Updates all attributes of {to} from their values at {from} - including primary type and mixins.
     * Protected attributes are ignored (e.g. jcr:uuid can't be set) - see {@link #ignoreAttribute(ResourceHandle, String, boolean)}.
     * We implement this using JCR directly, since {@link ModifiableValueMap#put(Object, Object)} breaks on references.
     *
     * @param from the source
     * @param to   the destination which we update
     * @throws RepositoryException if we couldn't finish the operation
     * @see #ignoreAttribute(ResourceHandle, String, boolean)
     */
    protected void updateAttributes(ResourceHandle from, ResourceHandle to) throws RepositoryException {
        ValueMap fromAttributes = ResourceUtil.getValueMap(from);
        ModifiableValueMap toAttributes = to.adaptTo(ModifiableValueMap.class);
        // first copy type information since this changes attributes. Use valuemap because of mixin handling
        toAttributes.put(PROP_PRIMARY_TYPE, fromAttributes.get(PROP_PRIMARY_TYPE));
        toAttributes.put(PROP_MIXINTYPES, fromAttributes.get(PROP_MIXINTYPES, new String[0]));

        // use nodes for others since it seems hard to handle types REFERENCE and WEAKREFERENCE correctly through ValueMap.
        Node fromNode = Objects.requireNonNull(from.getNode(), from.getPath());
        Node toNode = Objects.requireNonNull(to.getNode(), to.getPath());

        for (PropertyIterator fromProperties = fromNode.getProperties(); fromProperties.hasNext(); ) {
            Property prop = fromProperties.nextProperty();
            String name = prop.getName();
            if (!ignoreAttribute(from, name, true)) {
                if (prop.isMultiple()) {
                    Value[] values = prop.getValues();
                    try {
                        toNode.setProperty(name, values);
                    } catch (IllegalArgumentException | RepositoryException e) { // probably extend protectedMetadataAttributes
                        LOG.info("Could not copy probably protected multiple attribute {} - {}", name, e.toString());
                    }
                } else {
                    Value value = prop.getValue();
                    try {
                        toNode.setProperty(name, value);
                    } catch (IllegalArgumentException | RepositoryException e) { // probably extend protectedMetadataAttributes
                        LOG.info("Could not copy probably protected single attribute {} - {}", name, e.toString());
                    }
                }
            }
        }

        Collection<String> toremove = CollectionUtils.subtract(toAttributes.keySet(), fromAttributes.keySet());
        for (PropertyIterator toProperties = toNode.getProperties(); toProperties.hasNext(); ) {
            Property toProp = toProperties.nextProperty();
            String name = toProp.getName();
            if (toremove.contains(name)) {
                try {
                    toProp.remove();
                } catch (IllegalArgumentException | RepositoryException e) {
                    // shouldn't be possible - how come that it isn't there on node from?
                    LOG.error("Couldn't copy remove protected attribute {} - {}", name, e.toString());
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
                    "jcr:versionHistory", "jcr:predecessors", "jcr:mergeFailed", "jcr:configuration",
                    JcrConstants.JCR_PRIMARYTYPE, JcrConstants.JCR_MIXINTYPES)));

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
    protected void updateChildren(ResourceHandle from, ResourceHandle to) throws RepositoryException, PersistenceException {
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
                updateSubtree(ResourceHandle.use(fromchild), ResourceHandle.use(tochild));
            } else {
                updateSubtree(ResourceHandle.use(fromchild), ResourceHandle.use(to.getChild(fromchild.getName())));
            }
        }
    }

}

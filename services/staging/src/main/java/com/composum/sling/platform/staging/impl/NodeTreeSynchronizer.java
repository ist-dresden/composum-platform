package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        try {
            updateAttributes(from, to, ImmutableBiMap.of());
            updateChildren(from, to);
        } catch (RuntimeException | RepositoryException | PersistenceException e) {
            LOG.warn("Exception during updating from {} : {}", SlingResourceUtil.getPath(from), e.toString());
            throw e;
        }
    }

    /**
     * Updates all attributes of {to} from their values at {from} - including primary type and mixins.
     * Protected attributes are ignored (e.g. jcr:uuid can't be set) - see {@link #ignoreAttribute(ResourceHandle, String, boolean)}.
     * We implement this using JCR directly, since {@link ModifiableValueMap#put(Object, Object)} breaks on references.
     *
     * @param from the source
     * @param to   the destination which we update
     * @param attributeNameTranslation any attribute contained in this map will be written to the attribute according to the value in the destination.
     *                                 The primary type or mixin types of the destination will only be touched if it's not translated
     *                                 or translated from a (different) attribute.
     * @throws RepositoryException if we couldn't finish the operation
     * @see #ignoreAttribute(ResourceHandle, String, boolean)
     * @return true when there were any differences in the attributes
     */
    public boolean updateAttributes(@Nonnull ResourceHandle from, @Nonnull ResourceHandle to,
                                    @Nonnull BiMap<String, String> attributeNameTranslation) throws RepositoryException {
        boolean attributesChanged = false;
        ValueMap fromAttributes = ResourceUtil.getValueMap(from);
        ModifiableValueMap toAttributes = to.adaptTo(ModifiableValueMap.class);
        // first copy type information since this changes attributes. Use valuemap because of its mixin handling
        // if the primary type is mapped to something (else), we don't touch the primary type of the destination
        // if the primary type is read from something, we do it now.
        boolean primaryKeyRelevant = !attributeNameTranslation.containsKey(PROP_PRIMARY_TYPE) || attributeNameTranslation.inverse().containsKey(PROP_PRIMARY_TYPE);
        if (primaryKeyRelevant) {
            Object newValue = fromAttributes.get(attributeNameTranslation.inverse().getOrDefault(PROP_PRIMARY_TYPE, PROP_PRIMARY_TYPE));
            Object origValue = toAttributes.put(PROP_PRIMARY_TYPE, newValue);
            attributesChanged = attributesChanged || !Objects.equals(newValue, origValue);
        }
        boolean mixinsRelevant = !attributeNameTranslation.containsKey(PROP_MIXINTYPES) || attributeNameTranslation.inverse().containsKey(PROP_MIXINTYPES);
        if (mixinsRelevant) {
            String[] newValue = fromAttributes.get(attributeNameTranslation.inverse().getOrDefault(PROP_MIXINTYPES, PROP_MIXINTYPES), new String[0]);
            Object origValue = toAttributes.put(PROP_MIXINTYPES, newValue);
            attributesChanged = attributesChanged || !Objects.deepEquals(newValue, origValue);
        }

        // use nodes for others since it seems hard to handle types REFERENCE and WEAKREFERENCE correctly through ValueMap.
        Node fromNode = Objects.requireNonNull(from.getNode(), from.getPath());
        Node toNode = Objects.requireNonNull(to.getNode(), to.getPath());
        Collection<String> toRemove = new HashSet<>(toAttributes.keySet());

        for (PropertyIterator fromProperties = fromNode.getProperties(); fromProperties.hasNext(); ) {
            Property prop = fromProperties.nextProperty();
            String name = prop.getName();
            String toname = attributeNameTranslation.getOrDefault(name, name);
            toRemove.remove(toname);
            if (!ignoreAttribute(from, name, true) || attributeNameTranslation.containsKey(name)) {
                // an attributeNameTranslation overrides ignoreAttribute since it's the current parameter
                if (prop.isMultiple()) {
                    Value[] values = prop.getValues();
                    attributesChanged = attributesChanged || !toNode.hasProperty(toname);
                    attributesChanged = attributesChanged || !Objects.deepEquals(values, toNode.getProperty(toname).getValues());
                    try {
                        toNode.setProperty(toname, values);
                    } catch (IllegalArgumentException | RepositoryException e) { // probably extend protectedMetadataAttributes
                        LOG.info("Could not copy to probably protected multiple attribute {} - {}", toname, e.toString());
                    }
                } else {
                    Value value = prop.getValue();
                    attributesChanged = attributesChanged || !toNode.hasProperty(toname);
                    attributesChanged = attributesChanged || !Objects.deepEquals(value, toNode.getProperty(toname).getValue());
                    try {
                        toNode.setProperty(toname, value);
                    } catch (IllegalArgumentException | RepositoryException e) { // probably extend protectedMetadataAttributes
                        LOG.info("Could not copy to probably protected single attribute {} - {}", toname, e.toString());
                    }
                }
            }
        }

        toRemove.remove(PROP_PRIMARY_TYPE); // would be bad idea
        if (!mixinsRelevant) { toRemove.remove(PROP_MIXINTYPES); }
        toRemove.removeAll(additionalIgnoredAttributes);

        for (PropertyIterator toProperties = toNode.getProperties(); toProperties.hasNext(); ) {
            Property toProp = toProperties.nextProperty();
            String name = toProp.getName();
            if (toRemove.contains(name)) {
                try {
                    toProp.remove();
                    attributesChanged = true;
                } catch (IllegalArgumentException | RepositoryException e) {
                    // shouldn't be possible - how come that it isn't there on node from?
                    LOG.error("Could not remove protected attribute {} from {} - {}", name, SlingResourceUtil.getPath(from), e.toString());
                }
            }
        }
        return attributesChanged;
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

    protected final Set<String> additionalIgnoredAttributes = new HashSet<>();

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
        return source && protectedMetadataAttributes.contains(attributename) ||
                additionalIgnoredAttributes.contains(attributename);
    }

    /**
     * Add additional attributes that are not synchronized (modifying this instance).
     *
     * @return this for builder style
     */
    @Nonnull
    public NodeTreeSynchronizer addIgnoredAttributes(@Nonnull String... attributes) {
        additionalIgnoredAttributes.addAll(Arrays.asList(attributes));
        return this;
    }

    /**
     * Creates / Updates / Deletes the children of {to} according to {from}. The attributes of {from} are ignored,
     * the attributes of the children are handled.
     */
    protected void updateChildren(ResourceHandle from, ResourceHandle to) throws RepositoryException, PersistenceException {
        boolean recursion = false;
        if (to.getPath().startsWith(from.getPath() + "/")) { // to is inside from
            String difference = to.getPath().substring(from.getPath().length());
            if (from.getPath().endsWith(difference))
                return; // we are already inside a recursion. Not pretty, but at least doesn't crash.
        }

        List<Resource> fromChildren = IteratorUtils.toList(from.listChildren());
        List<String> fromChildrenNames = fromChildren.stream()
                .filter(n -> !skipNode(n))
                .map(Resource::getName)
                .collect(Collectors.toList());
        for (Resource child : to.getChildren()) {
            if (!fromChildrenNames.contains(child.getName()))
                child.getResourceResolver().delete(child);
        }
        List<String> toChildrenNames = IteratorUtils.toList(to.listChildren())
                .stream().map(Resource::getName).collect(Collectors.toList());
        for (Resource fromchild : from.getChildren()) {
            if (skipNode(fromchild)) continue;
            if (!toChildrenNames.contains(fromchild.getName())) {
                Resource tochild = to.getResourceResolver().create(to, fromchild.getName(), null);
                updateSubtree(ResourceHandle.use(fromchild), ResourceHandle.use(tochild));
            } else {
                updateSubtree(ResourceHandle.use(fromchild), ResourceHandle.use(to.getChild(fromchild.getName())));
            }
        }
    }

    /**
     * Can be overridden to remove nodes from the source - nodes matching this aren't copied. Default: just nodes with
     * primary type rep:ACL (acls can't be copied this way.)
     *
     * @return true if the node shall be skipped.
     */
    protected boolean skipNode(@Nonnull Resource from) {
        if ("rep:ACL".equals(from.getValueMap().get(CoreConstants.JCR_PRIMARYTYPE, String.class))) {
            LOG.warn("Not duplicating ACL in {}", SlingResourceUtil.getPath(from));
            return true;
        }
        return false;
    }

}

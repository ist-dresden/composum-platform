package com.composum.sling.platform.staging.impl;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.commons.ItemNameMatcher;
import org.apache.jackrabbit.commons.iterator.NodeIteratorAdapter;
import org.apache.jackrabbit.commons.iterator.PropertyIteratorAdapter;
import org.apache.sling.api.resource.Resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.AccessDeniedException;
import javax.jcr.Binary;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidLifecycleTransitionException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.MergeException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.Workspace;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.version.ActivityViolationException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static com.composum.sling.platform.staging.StagingConstants.FROZEN_PROP_NAMES_TO_REAL_NAMES;
import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;
import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;

/**
 * (Not quite complete) wrapper for {@link Node} that disables writing.
 * That's neccesary since some functions rely on adaptability to {@link Node}.
 * <p>
 * Methods that are difficult / errorprone to implement and are likely not needed throw an {@link UnsupportedRepositoryOperationException}.
 * We'd rather throw an exception than return a wrong value.
 * If that occurs somewhere please complain, and it'll be done.
 */
@SuppressWarnings({"RedundantThrows", "DuplicateThrows", "deprecation"})
public class FrozenNodeWrapper extends AbstractFrozenItem<Node> implements Node {

    /**
     * Path to the version property from a top level frozenNode of a version - represents
     * {@link StagingConstants#PROP_REPLICATED_VERSION}.
     */
    protected static final String TOP_FROZENNODE_PROP_REPLICATEDVERSION = "../" + ResourceUtil.JCR_UUID;

    @NotNull
    private final Resource resource;
    private transient Boolean storedVersionTopNode;
    private transient Boolean inStorage;

    FrozenNodeWrapper(@NotNull Node wrapped, @NotNull Resource resource) {
        super(wrapped, resource.getPath());
        this.resource = resource;
    }

    @Nullable
    public static FrozenNodeWrapper wrap(@Nullable Node wrapped, @NotNull Resource resource) {
        if (wrapped == null) { return null; }
        if (wrapped instanceof FrozenNodeWrapper) {
            Node unwrapped = ((FrozenNodeWrapper) wrapped).getWrapped();
            try {
                if (wrapped.getPath().equals(resource.getPath())) {
                    throw new IllegalArgumentException("Something's broken: wrapping " + wrapped.getPath() + " as " + resource.getPath());
                }
            } catch (RepositoryException e) {
                throw new IllegalArgumentException(e);
            }
            return FrozenNodeWrapper.wrap(unwrapped, resource);
        }
        return new FrozenNodeWrapper(wrapped, resource);
    }

    protected boolean isInStorage() throws RepositoryException {
        if (inStorage == null) {
            inStorage = StagingUtils.isInStorage(wrapped);
        }
        return inStorage;
    }

    protected boolean isStoredVersionTopNode() throws RepositoryException {
        if (storedVersionTopNode == null) {
            storedVersionTopNode = StagingUtils.isStoredVersionTopNode(wrapped);
        }
        return storedVersionTopNode;
    }

    @Override
    public Node getNode(String relPath) throws PathNotFoundException, RepositoryException {
        Resource r = resource.getChild(relPath);
        if (r == null) { throw new PathNotFoundException("Can't find " + getPath() + " - " + relPath); }
        Node node = r.adaptTo(Node.class);
        if (node == null) { throw unsupported(); }
        return node;
    }

    @Override
    public NodeIterator getNodes() throws RepositoryException {
        // We go through the resolver since the simulated children for staging might be in version space or something.
        Iterator<Resource> resourceChildren = resource.listChildren();
        Iterator<Node> childrenNodes = IteratorUtils.transformedIterator(resourceChildren, (r) -> r.adaptTo(Node.class));
        return new NodeIteratorAdapter(childrenNodes);
    }

    @Override
    public NodeIterator getNodes(String namePattern) throws RepositoryException {
        NodeIterator nodes = wrapped.getNodes(namePattern);
        return rewrapByPath(nodes);
    }

    /** We go through the resolver since the simulated children for staging might be in version space or something. */
    @SuppressWarnings("unchecked")
    @NotNull
    protected NodeIterator rewrapByPath(NodeIterator nodes) throws RepositoryException {
        return new NodeIteratorAdapter(
                IteratorUtils.transformedIterator(nodes,
                        (Node n) -> ExceptionUtil.callAndSneakExceptions(
                                () -> Objects.requireNonNull(resource.getChild(n.getName()), n.getPath())
                                        .adaptTo(Node.class))));
    }

    @Override
    public NodeIterator getNodes(String[] nameGlobs) throws RepositoryException {
        return rewrapByPath(wrapped.getNodes(nameGlobs));
    }

    @Override
    public Property getProperty(String relPath) throws PathNotFoundException, RepositoryException {
        if (relPath.contains("/")) {
            Resource child = resource.getChild(relPath); // property resourc
            Property prop = null;
            if (child != null) {
                prop = child.adaptTo(Property.class);
                if (prop == null) { throw new PathNotFoundException("Can't find " + getPath() + " - " + relPath); }
            }
            return prop;
        }
        if (ResourceUtil.PROP_MIXINTYPES.equals(relPath) && isStoredVersionTopNode()) {
            return mixinsWithMixReplicatedVersionable();
        }
        String realName;
        if (PROP_REPLICATED_VERSION.equals(relPath) && isStoredVersionTopNode()) {
            realName = TOP_FROZENNODE_PROP_REPLICATEDVERSION;
        } else {
            realName = isInStorage() ?
                    REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(relPath, relPath)
                    : relPath;
        }
        Property prop = wrapped.getProperty(realName);
        return FrozenPropertyWrapper.wrap(prop, getPath() + "/" + relPath);
    }

    @Override
    public PropertyIterator getProperties() throws RepositoryException {
        PropertyIterator properties = wrapped.getProperties();
        PropertyIterator result = rewrapIntoWrappedProperty(properties);
        if (isStoredVersionTopNode()) {
            result = addCplReplicatedVersionProperty(result);
        }
        return result;
    }

    /** Creates a property that adds the mixin {@link StagingConstants#TYPE_MIX_REPLICATEDVERSIONABLE} to the mixins. */
    protected Property mixinsWithMixReplicatedVersionable() throws RepositoryException {
        String path = resource.getPath() + "/" + ResourceUtil.PROP_MIXINTYPES;
        List<String> values = new ArrayList<>();
        if (wrapped.hasProperty(JcrConstants.JCR_FROZENMIXINTYPES)) {
            Property frozenProp = wrapped.getProperty(JcrConstants.JCR_FROZENMIXINTYPES);
            for (Value value : frozenProp.getValues()) {
                values.add(value.getString());
            }
        }
        if (!values.contains(StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE)) {
            values.add(StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE);
        }
        return new SimulatedMixinProperty(path, wrapped.getSession(), values);
    }

    @NotNull
    protected PropertyIterator addCplReplicatedVersionProperty(PropertyIterator propertyIterator) throws RepositoryException {
        FrozenPropertyWrapper replproperty = FrozenPropertyWrapper.wrap(wrapped.getProperty(TOP_FROZENNODE_PROP_REPLICATEDVERSION),
                resource.getPath() + "/" + PROP_REPLICATED_VERSION);
        Property mixinProperty = mixinsWithMixReplicatedVersionable();
        return new PropertyIteratorAdapter(IteratorUtils.chainedIterator(
                IteratorUtils.<Property>filteredIterator(propertyIterator, this::notMixinProperty),
                IteratorUtils.arrayIterator(replproperty, mixinProperty)
        ));
    }

    protected boolean notMixinProperty(Property property) {
        try {
            return !ResourceUtil.PROP_MIXINTYPES.equals(property.getName());
        } catch (RepositoryException e) { // should be impossible
            throw new IllegalStateException("Should be impossible: " + e, e);
        }
    }

    @SuppressWarnings({"unchecked", "ConstantConditions"})
    @NotNull
    protected PropertyIterator rewrapIntoWrappedProperty(PropertyIterator properties) throws RepositoryException {
        Iterator filteredproperties = properties;
        if (isInStorage()) {
            filteredproperties = IteratorUtils.filteredIterator(properties,
                    (Property p) ->
                            ExceptionUtil.callAndSneakExceptions(
                                    () -> !REAL_PROPNAMES_TO_FROZEN_NAMES.keySet().contains(p.getName()))
            );
        }
        Iterator renamedProps = IteratorUtils.transformedIterator(filteredproperties,
                (Property p) -> ExceptionUtil.callAndSneakExceptions(() -> {
                    String simulatedName = FROZEN_PROP_NAMES_TO_REAL_NAMES.getOrDefault(p.getName(), p.getName());
                    return FrozenPropertyWrapper.wrap(p,
                            resource.getPath() + "/" + simulatedName);
                })
        );
        return new PropertyIteratorAdapter(
                renamedProps
        );
    }

    @Override
    public PropertyIterator getProperties(String namePattern) throws RepositoryException {
        PropertyIterator result = rewrapIntoWrappedProperty(wrapped.getProperties(namePattern));
        if (ItemNameMatcher.matches(PROP_REPLICATED_VERSION, namePattern) && isStoredVersionTopNode()) {
            result = addCplReplicatedVersionProperty(result);
        }
        return result;
    }

    @Override
    public PropertyIterator getProperties(String[] nameGlobs) throws RepositoryException {
        PropertyIterator result = rewrapIntoWrappedProperty(wrapped.getProperties(nameGlobs));
        if (ItemNameMatcher.matches(PROP_REPLICATED_VERSION, nameGlobs) && isStoredVersionTopNode()) {
            result = addCplReplicatedVersionProperty(result);
        }
        return result;
    }

    @Override
    @Deprecated
    public String getUUID() throws UnsupportedRepositoryOperationException, RepositoryException {
        try {
            Property uuidProp = getProperty(ResourceUtil.PROP_UUID);
            return uuidProp.getString();
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    @Override
    public int getIndex() throws RepositoryException {
        return wrapped.getIndex();
    }

    @Override
    public boolean hasNode(String relPath) throws RepositoryException {
        try {
            return getNode(relPath) != null;
        } catch (PathNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean hasProperty(String relPath) throws RepositoryException {
        try {
            return getProperty(relPath) != null;
        } catch (PathNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean hasNodes() throws RepositoryException {
        return getNodes().hasNext();
    }

    @Override
    public boolean hasProperties() throws RepositoryException {
        return getProperties().hasNext();
    }

    @Override
    public final Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        Resource parent = resource.getParent();
        Node n = parent != null ? parent.adaptTo(Node.class) : null;
        if (n == null) { throw new ItemNotFoundException("Could not get parent of " + getPath()); }
        return n;
    }

    @Override
    public NodeType getPrimaryNodeType() throws RepositoryException {
        try {
            return getNodeTypeManager()
                    .getNodeType(getProperty(ResourceUtil.PROP_PRIMARY_TYPE).getString());
        } catch (PathNotFoundException e) {
            return null; // no primary type property
        }
    }

    @Override
    public NodeType[] getMixinNodeTypes() throws RepositoryException {
        Property mixinProp;
        try {
            mixinProp = getProperty(ResourceUtil.PROP_MIXINTYPES);
        } catch (PathNotFoundException e) {
            return new NodeType[0];
        }
        NodeTypeManager nodeTypeManager = getNodeTypeManager();
        List<NodeType> result = new ArrayList<>();
        for (Value val : mixinProp.getValues()) {
            result.add(nodeTypeManager.getNodeType(val.getString()));
        }
        return result.toArray(new NodeType[0]);
    }

    @Override
    public boolean isNodeType(String nodeTypeName) throws RepositoryException {
        NodeType primaryNodeType = getPrimaryNodeType();
        if (primaryNodeType != null && primaryNodeType.isNodeType(nodeTypeName)) { return true; }
        for (NodeType nodeType : getMixinNodeTypes()) {
            if (nodeType.isNodeType(nodeTypeName)) { return true; }
        }
        return false;
    }

    @NotNull
    protected NodeTypeManager getNodeTypeManager() throws RepositoryException {
        try {
            Session session = this.wrapped.getSession();
            Workspace workspace = session.getWorkspace();
            return Objects.requireNonNull(workspace.getNodeTypeManager());
        } catch (NullPointerException e) {
            throw new RepositoryException("Cannot get NodeTypeManager", e);
        }
    }

    // ========================= Stuff we probably don't need, but what could in theory be supported.

    @Override
    public String getIdentifier() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public void followLifecycleTransition(String transition) throws UnsupportedRepositoryOperationException, InvalidLifecycleTransitionException, RepositoryException {
        throw unsupported();
    }

    @Override
    public String[] getAllowedLifecycleTransistions() throws UnsupportedRepositoryOperationException, RepositoryException {
        throw unsupported();
    }

    @Override
    public String getCorrespondingNodePath(String workspaceName) throws ItemNotFoundException, NoSuchWorkspaceException, AccessDeniedException, RepositoryException {
        throw unsupported();
    }

    @Override
    public NodeIterator getSharedSet() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public VersionHistory getVersionHistory() throws UnsupportedRepositoryOperationException, RepositoryException {
        throw unsupported();
    }

    @Override
    public Version getBaseVersion() throws UnsupportedRepositoryOperationException, RepositoryException {
        throw unsupported();
    }

    @Override
    public NodeDefinition getDefinition() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public PropertyIterator getReferences() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public PropertyIterator getReferences(String name) throws RepositoryException {
        throw unsupported();
    }

    @Override
    public PropertyIterator getWeakReferences() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public PropertyIterator getWeakReferences(String name) throws RepositoryException {
        throw unsupported();
    }

    @Override
    public Item getPrimaryItem() throws ItemNotFoundException, RepositoryException {
        throw unsupported();
    }

    // ========================= the easy cases : modifying functions =======================

    @Override
    public boolean holdsLock() throws RepositoryException {
        return false;
    }

    @Override
    public boolean isLocked() throws RepositoryException {
        return false;
    }

    @Override
    public Lock lock(boolean isDeep, boolean isSessionScoped) throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Lock getLock() throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void unlock() throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Node addNode(String relPath) throws ItemExistsException, PathNotFoundException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Node addNode(String relPath, String primaryNodeTypeName) throws ItemExistsException, PathNotFoundException, NoSuchNodeTypeException, LockException, VersionException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void orderBefore(String srcChildRelPath, String destChildRelPath) throws UnsupportedRepositoryOperationException, VersionException, ConstraintViolationException, ItemNotFoundException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Value value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Value value, int type) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Value[] values) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Value[] values, int type) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, String[] values) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, String[] values, int type) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, String value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, String value, int type) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, InputStream value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Binary value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, boolean value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, double value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, BigDecimal value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, long value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Calendar value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Property setProperty(String name, Node value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setPrimaryType(String nodeTypeName) throws NoSuchNodeTypeException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void addMixin(String mixinName) throws NoSuchNodeTypeException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void removeMixin(String mixinName) throws NoSuchNodeTypeException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public boolean canAddMixin(String mixinName) throws NoSuchNodeTypeException, RepositoryException {
        return false;
    }

    @Override
    public Version checkin() throws VersionException, UnsupportedRepositoryOperationException, InvalidItemStateException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void checkout() throws UnsupportedRepositoryOperationException, LockException, ActivityViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void doneMerge(Version version) throws VersionException, InvalidItemStateException, UnsupportedRepositoryOperationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void cancelMerge(Version version) throws VersionException, InvalidItemStateException, UnsupportedRepositoryOperationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void update(String srcWorkspace) throws NoSuchWorkspaceException, AccessDeniedException, LockException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public NodeIterator merge(String srcWorkspace, boolean bestEffort) throws NoSuchWorkspaceException, AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void removeSharedSet() throws VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void removeShare() throws VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public boolean isCheckedOut() throws RepositoryException {
        return false;
    }

    @Override
    public void restore(String versionName, boolean removeExisting) throws VersionException, ItemExistsException, UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void restore(Version version, boolean removeExisting) throws VersionException, ItemExistsException, InvalidItemStateException, UnsupportedRepositoryOperationException, LockException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void restore(Version version, String relPath, boolean removeExisting) throws PathNotFoundException, ItemExistsException, VersionException, ConstraintViolationException, UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void restoreByLabel(String versionLabel, boolean removeExisting) throws VersionException, ItemExistsException, UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

}

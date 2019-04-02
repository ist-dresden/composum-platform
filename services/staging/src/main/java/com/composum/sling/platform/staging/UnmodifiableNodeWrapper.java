package com.composum.sling.platform.staging;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.exception.ContextedRuntimeException;
import org.apache.jackrabbit.commons.iterator.NodeIteratorAdapter;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.*;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.ActivityViolationException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;

/**
 * (Not quite complete) wrapper for {@link Node} that disables writing.
 * That's neccesary since some functions rely on adaptability to {@link Node}.
 * <p>
 * Methods that are difficult / errorprone to implement and are likely not needed throw an {@link UnsupportedRepositoryOperationException}.
 * We'd rather throw an exception than return a wrong value.
 * If that occurs somewhere please complain, and it'll be done.
 */
class UnmodifiableNodeWrapper extends AbstractUnmodifiableItem<Node> implements Node {

    @Nonnull
    private final Resource resource;

    UnmodifiableNodeWrapper(@Nonnull Node wrapped, @Nonnull Resource resource) {
        super(wrapped, resource.getPath());
        this.resource = resource;
    }

    @Nullable
    public static UnmodifiableNodeWrapper wrap(@Nullable Node wrapped, @Nonnull Resource resource) {
        if (wrapped == null)
            return null;
        if (wrapped instanceof UnmodifiableNodeWrapper) {
            Node unwrapped = ((UnmodifiableNodeWrapper) wrapped).getWrapped();
            try {
                if (wrapped.getPath().equals(resource.getPath()))
                    throw new IllegalArgumentException("Something's broken: wrapping " + wrapped.getPath() + " as " + resource.getPath());
            } catch (RepositoryException e) {
                throw new IllegalStateException(e);
            }
            return new UnmodifiableNodeWrapper(unwrapped, resource);
        }
        return new UnmodifiableNodeWrapper(wrapped, resource);
    }

    @Override
    public Node getNode(String relPath) throws PathNotFoundException, RepositoryException {
        Resource r = resource.getChild(relPath);
        if (r == null) throw new PathNotFoundException("Can't find " + getPath() + " - " + relPath);
        Node node = r.adaptTo(Node.class);
        if (node == null) throw unsupported();
        return node;
    }

    @Override
    public NodeIterator getNodes() throws RepositoryException {
        // We go through the resolver since the simulated children for staging might be in version space or something.
        return new NodeIteratorAdapter(
                IteratorUtils.transformedIterator(resource.listChildren(), (r) -> r.adaptTo(Node.class)));
    }

    @Override
    public NodeIterator getNodes(String namePattern) throws RepositoryException {
        NodeIterator nodes = wrapped.getNodes(namePattern);
        return rewrapByPath(nodes);
    }

    /** We go through the resolver since the simulated children for staging might be in version space or something. */
    @Nonnull
    protected NodeIterator rewrapByPath(NodeIterator nodes) throws RepositoryException {
        try {
            return new NodeIteratorAdapter(
                    IteratorUtils.transformedIterator(nodes,
                            (Node n) -> {
                                try {
                                    return resource.getChild(n.getName()).adaptTo(Node.class);
                                } catch (RepositoryException e) {
                                    throw new ContextedRuntimeException(e);
                                }
                            }
                    ));
        } catch (ContextedRuntimeException e) {
            if (e.getCause() instanceof RepositoryException) throw (RepositoryException) e.getCause();
            throw e;
        }
    }

    @Override
    public NodeIterator getNodes(String[] nameGlobs) throws RepositoryException {
        return rewrapByPath(wrapped.getNodes(nameGlobs));
    }

    @Override
    public Property getProperty(String relPath) throws PathNotFoundException, RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
    }

    @Override
    public PropertyIterator getProperties() throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
    }

    @Override
    public PropertyIterator getProperties(String namePattern) throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
    }

    @Override
    public PropertyIterator getProperties(String[] nameGlobs) throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
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
    public NodeType getPrimaryNodeType() throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
    }

    @Override
    public NodeType[] getMixinNodeTypes() throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
    }

    @Override
    public boolean isNodeType(String nodeTypeName) throws RepositoryException {
        throw new UnsupportedOperationException("Not implemented yet."); // FIXME hps 2019-04-02 not implemented
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

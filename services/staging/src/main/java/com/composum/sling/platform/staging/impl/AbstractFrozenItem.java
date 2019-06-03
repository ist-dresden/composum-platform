package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.*;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.version.VersionException;

/** Common methods for {@link FrozenNodeWrapper} and {@link FrozenPropertyWrapper}. */
@SuppressWarnings({"RedundantThrows", "DuplicateThrows"})
public abstract class AbstractFrozenItem<T extends Item> implements Item {


    @Nonnull
    protected final T wrapped;

    /** If non null, we override the path since we might wrap frozen nodes. */
    @Nullable
    protected final String pathOverride;

    protected AbstractFrozenItem(@Nonnull T wrapped, @Nullable String pathOverride) {
        this.wrapped = wrapped;
        this.pathOverride = pathOverride;
    }

    @Nonnull
    final T getWrapped() {
        return wrapped;
    }

    /** We throw this for operations that would modify things, and thus are not implemented. */
    protected final UnsupportedRepositoryOperationException unmodifiable() throws UnsupportedRepositoryOperationException {
        throw new UnsupportedRepositoryOperationException("Staged resources are not modifiable");
    }

    /**
     * We throw this for operations that likely won't be needed, but could in theory be supported, but that'd be difficult / error prone.
     * Motto: we rather throw an exception than to return wrong results.
     */
    protected final UnsupportedRepositoryOperationException unsupported() throws UnsupportedRepositoryOperationException {
        throw new UnsupportedRepositoryOperationException("Operation not supported (yet) for staged resources");
    }

    @Override
    public final String getPath() throws RepositoryException {
        return pathOverride != null ? pathOverride : wrapped.getPath();
    }

    @Override
    public final String getName() throws RepositoryException {
        return pathOverride != null ? ResourceUtil.getName(pathOverride) : wrapped.getName();
    }

    @Override
    public final Session getSession() throws RepositoryException {
        return wrapped.getSession();
    }

    @Override
    public final boolean isNode() {
        return wrapped.isNode();
    }

    @Override
    public final boolean isNew() {
        return wrapped.isNew();
    }

    @Override
    public final boolean isModified() {
        return wrapped.isModified();
    }

    @Override
    public final boolean isSame(Item otherItem) throws RepositoryException {
        return wrapped.isSame(otherItem);
    }

    @Override
    public final void accept(ItemVisitor visitor) throws RepositoryException {
        if (this instanceof Node) visitor.visit((Node) this);
        if (this instanceof Property) visitor.visit((Property) this);
    }

    @Override
    @Deprecated
    public final void save() throws AccessDeniedException, ItemExistsException, ConstraintViolationException, InvalidItemStateException, ReferentialIntegrityException, VersionException, LockException, NoSuchNodeTypeException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public final void refresh(boolean keepChanges) throws InvalidItemStateException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public final void remove() throws VersionException, LockException, ConstraintViolationException, AccessDeniedException, RepositoryException {
        throw unmodifiable();
    }

    // =================   stuff that's probably not needed - difficult or unfeasible. Implemented as needed.

    @Override
    public final Item getAncestor(int depth) throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        throw unsupported();
    }

    @Override
    public Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        throw unsupported();
    }

    @Override
    public final int getDepth() throws RepositoryException {
        throw unsupported();
    }


}

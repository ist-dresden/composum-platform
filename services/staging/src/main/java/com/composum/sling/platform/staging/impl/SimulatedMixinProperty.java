package com.composum.sling.platform.staging.impl;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.sling.api.resource.ResourceUtil;

import org.jetbrains.annotations.NotNull;
import javax.jcr.Binary;
import javax.jcr.Item;
import javax.jcr.ItemVisitor;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.PropertyDefinition;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.List;

/**
 * Simulates a mixin property that adds a mixin (namely
 * {@value com.composum.sling.platform.staging.StagingConstants#TYPE_MIX_REPLICATEDVERSIONABLE})
 * to the nodes actual mixins.
 */
class SimulatedMixinProperty implements Property {

    @NotNull
    protected final String path;

    @NotNull
    protected final Session session;

    @NotNull
    protected final Value[] values;

    SimulatedMixinProperty(@NotNull String path, @NotNull Session session, @NotNull List<String> mixins) {
        this.path = path;
        this.session = session;
        values = mixins.stream().map(StringValue::new).toArray(Value[]::new);
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

    protected final ValueFormatException invalidValueFormat() throws ValueFormatException {
        throw new ValueFormatException("This is a multiple String property: " + path);
    }

    @NotNull
    @Override
    public final String getPath() throws RepositoryException {
        return path;
    }

    @Override
    public final String getName() throws RepositoryException {
        return ResourceUtil.getName(path);
    }

    @NotNull
    @Override
    public final Session getSession() throws RepositoryException {
        return session;
    }

    @Override
    public boolean isMultiple() throws RepositoryException {
        return true;
    }

    @Override
    public int getType() throws RepositoryException {
        return PropertyType.STRING;
    }

    @Override
    public Value[] getValues() throws RepositoryException {
        return values;
    }

    @Override
    public final boolean isNode() {
        return false;
    }

    @Override
    public final boolean isNew() {
        return false;
    }

    @Override
    public final boolean isModified() {
        return false;
    }

    @Override
    public final void accept(ItemVisitor visitor) throws RepositoryException {
        if (this instanceof Property) { visitor.visit(this); }
    }

    @Override
    @Deprecated
    public final void save() throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public final void refresh(boolean keepChanges) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public final void remove() throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public String toString() {
        ToStringBuilder toStringBuilder = new ToStringBuilder(this);
        toStringBuilder.append("path", path);
        toStringBuilder.append("values", values);
        return toStringBuilder.toString();
    }

    // =================   stuff that's probably not needed - difficult or unfeasible. Implemented as needed.

    @Override
    public final boolean isSame(Item otherItem) throws RepositoryException {
        throw unsupported();
    }

    @Override
    public final Item getAncestor(int depth) throws RepositoryException {
        throw unsupported();
    }

    @Override
    public Node getParent() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public final int getDepth() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public PropertyDefinition getDefinition() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public Node getNode() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public Property getProperty() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public long getLength() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public long[] getLengths() throws RepositoryException {
        throw unsupported();
    }

    // ========================  easy cases: disallowed modifications


    @Override
    public void setValue(Value value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Value[] values) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(String value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(String[] values) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(InputStream value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Binary value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(long value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(double value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(BigDecimal value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Calendar value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(boolean value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Node value) throws RepositoryException {
        throw unmodifiable();
    }

    @Override
    public Value getValue() throws RepositoryException {
        throw invalidValueFormat();
    }

    // stuff unsupported since this is a multiple string

    @Override
    public String getString() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public InputStream getStream() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public Binary getBinary() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public long getLong() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public double getDouble() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public BigDecimal getDecimal() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public Calendar getDate() throws RepositoryException {
        throw invalidValueFormat();
    }

    @Override
    public boolean getBoolean() throws RepositoryException {
        throw invalidValueFormat();
    }

    /** Simulates a String property for the mixins. */
    protected class StringValue implements Value {

        protected final String value;

        public StringValue(String value) {
            this.value = value;
        }

        @Override
        public String getString() throws IllegalStateException, RepositoryException {
            return value;
        }

        @Override
        public InputStream getStream() throws RepositoryException {
            throw unsupported();
        }

        @Override
        public Binary getBinary() throws RepositoryException {
            throw unsupported();
        }

        @Override
        public long getLong() throws RepositoryException {
            throw invalidValueFormat();
        }

        @Override
        public double getDouble() throws RepositoryException {
            throw invalidValueFormat();
        }

        @Override
        public BigDecimal getDecimal() throws RepositoryException {
            throw invalidValueFormat();
        }

        @Override
        public Calendar getDate() throws RepositoryException {
            throw invalidValueFormat();
        }

        @Override
        public boolean getBoolean() throws RepositoryException {
            throw invalidValueFormat();
        }

        @Override
        public int getType() {
            return PropertyType.STRING;
        }
    }

}

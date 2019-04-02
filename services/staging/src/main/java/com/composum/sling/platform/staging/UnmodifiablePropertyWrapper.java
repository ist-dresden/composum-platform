package com.composum.sling.platform.staging;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.*;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.version.VersionException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;

/**
 * (Not quite complete) wrapper for {@link javax.jcr.Property} that disables writing.
 * That's neccesary since some functions rely on adaptability to {@link javax.jcr.Property}.
 * <p>
 * Methods that are difficult / errorprone to implement and are likely not needed throw an {@link UnsupportedRepositoryOperationException}.
 * We'd rather throw an exception than return a wrong value.
 * If that occurs somewhere please complain, and it'll be done.
 */
class UnmodifiablePropertyWrapper extends AbstractUnmodifiableItem<Property> implements Property {

    UnmodifiablePropertyWrapper(@Nonnull Property wrapped, String path) {
        super(wrapped, path);
    }

    @Nullable
    public static UnmodifiablePropertyWrapper wrap(@Nullable Property wrapped, String path) {
        if (wrapped == null)
            return null;
        if (wrapped instanceof UnmodifiablePropertyWrapper)
            return new UnmodifiablePropertyWrapper(((UnmodifiablePropertyWrapper) wrapped).getWrapped(), path);
        return new UnmodifiablePropertyWrapper(wrapped, path);
    }

    @Override
    public Value getValue() throws ValueFormatException, RepositoryException {
        return wrapped.getValue();
    }

    @Override
    public Value[] getValues() throws ValueFormatException, RepositoryException {
        return wrapped.getValues();
    }

    @Override
    public String getString() throws ValueFormatException, RepositoryException {
        return wrapped.getString();
    }

    @Override
    @Deprecated
    public InputStream getStream() throws ValueFormatException, RepositoryException {
        return wrapped.getStream();
    }

    @Override
    public Binary getBinary() throws ValueFormatException, RepositoryException {
        return wrapped.getBinary();
    }

    @Override
    public long getLong() throws ValueFormatException, RepositoryException {
        return wrapped.getLong();
    }

    @Override
    public double getDouble() throws ValueFormatException, RepositoryException {
        return wrapped.getDouble();
    }

    @Override
    public BigDecimal getDecimal() throws ValueFormatException, RepositoryException {
        return wrapped.getDecimal();
    }

    @Override
    public Calendar getDate() throws ValueFormatException, RepositoryException {
        return wrapped.getDate();
    }

    @Override
    public boolean getBoolean() throws ValueFormatException, RepositoryException {
        return wrapped.getBoolean();
    }

    @Override
    public long getLength() throws ValueFormatException, RepositoryException {
        return wrapped.getLength();
    }

    @Override
    public long[] getLengths() throws ValueFormatException, RepositoryException {
        return wrapped.getLengths();
    }

    @Override
    public int getType() throws RepositoryException {
        return wrapped.getType();
    }

    @Override
    public boolean isMultiple() throws RepositoryException {
        return wrapped.isMultiple();
    }

    // ======== Stuff we probably don't need, but what could in theory be supported but is difficult / errorprone, so we skip it for now

    @Override
    public PropertyDefinition getDefinition() throws RepositoryException {
        throw unsupported();
    }

    @Override
    public Node getNode() throws ItemNotFoundException, ValueFormatException, RepositoryException {
        throw unsupported();
    }

    @Override
    public Property getProperty() throws ItemNotFoundException, ValueFormatException, RepositoryException {
        throw unsupported();
    }

    // ========================  easy cases: disallowed modifications


    @Override
    public void setValue(Value value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Value[] values) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(String value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(String[] values) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(InputStream value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Binary value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(long value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(double value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(BigDecimal value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Calendar value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(boolean value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

    @Override
    public void setValue(Node value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        throw unmodifiable();
    }

}

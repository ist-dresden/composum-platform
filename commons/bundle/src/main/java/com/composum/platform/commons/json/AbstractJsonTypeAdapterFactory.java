package com.composum.platform.commons.json;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * Similar to the GSon {@link com.google.gson.TypeAdapter} but done so that it allows access to the gson and type
 * object without needing to go to through the two steps of creating a {@link TypeAdapterFactory} and create a
 * {@link com.google.gson.TypeAdapter} from that.
 */
public abstract class AbstractJsonTypeAdapterFactory<T> implements TypeAdapterFactory {

    @NotNull
    protected final TypeToken<T> type;

    protected AbstractJsonTypeAdapterFactory(@NotNull TypeToken<T> type) {
        this.type = type;
    }

    /**
     * Has to be overridden to serialize the value.
     *
     * @param requestedType the type for which GSon requested a type adapter; for the default implementation of
     *                      {@link #isResponsible(TypeToken)} this will be {@link #type} or a subtype.
     */
    protected <TR> void write(@NotNull JsonWriter out, @NotNull T value, @NotNull Gson gson,
                              @NotNull TypeToken<TR> requestedType) throws IOException {
        throw new UnsupportedOperationException("Serialization not implemented. " +
                "requestedType = " + requestedType + " , type = " + type);
    }

    /**
     * Has to be overridden to deserialize a value.
     *
     * @param requestedType the type for which GSon requested a type adapter; for the default implementation of
     *                      {@link #isResponsible(TypeToken)} this will be {@link #type} or a subtype. So rather use
     *                      this to create objects.
     * @return the deserialized value; must be compatible to {requestedType}
     */
    @Nullable
    protected <TR extends T> T read(@NotNull JsonReader in, @NotNull Gson gson, @NotNull TypeToken<TR> requestedType) throws IOException {
        throw new UnsupportedOperationException("Deserialization not implemented. " +
                "requestedType = " + requestedType + " , type = " + type);
    }

    /**
     * Checks whether this adapter is responsible for the type {requestedType}. Default: whether the
     * {@link TypeToken#getRawType()} is assignable to our type, which might be wrong for deserialization
     * and derived classes. Must not return true if TR is not T or a subtype of T.
     */
    protected <TR> boolean isResponsible(@NotNull TypeToken<TR> requestedType) {
        return type.getRawType().isAssignableFrom(requestedType.getRawType());
    }

    /**
     * Generates an instance of {requestedType} which must be a subtype of T.
     *
     * @throws IllegalStateException if this wasn't possible due to some bug.
     */
    @SuppressWarnings("unchecked")
    @NotNull
    protected <TR extends T> TR makeInstance(@NotNull TypeToken<TR> requestedType) throws IllegalStateException {
        try {
            return (TR) type.getRawType().cast(requestedType.getRawType().getConstructor().newInstance());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            LoggerFactory.getLogger(getClass()).error("Bug: misuse of this class - cannot construct " + requestedType, e);
            throw new IllegalStateException(e);
        }
    }


    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <TR> TypeAdapter<TR> create(Gson gson, TypeToken<TR> requestedType) {
        if (AbstractJsonTypeAdapterFactory.this.isResponsible(requestedType)) {
            return new TypeAdapter<TR>() {

                @Override
                public void write(JsonWriter out, TR value) throws IOException {
                    AbstractJsonTypeAdapterFactory.this.write(out, (T) type.getRawType().cast(value), gson,
                            requestedType);
                }

                @SuppressWarnings("rawtypes")
                @Override
                public TR read(JsonReader in) throws IOException {
                    return (TR) requestedType.getRawType().cast(AbstractJsonTypeAdapterFactory.this.read(in, gson,
                            (TypeToken) requestedType));
                }
            };
        }
        return null;
    }

}

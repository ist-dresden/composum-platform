package com.composum.platform.commons.json;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * An interface for classes who do GSon JSON serialization / deserialization themselves, even within the GSON
 * framework. Alternative to writing an adapter - might be make objects whose sole purpose is to be serialized to
 * JSON more concise, and allows access to the GSON object serializing it, and the type token (which can possibly
 * used to deduce type parameters if the object deriving from {@link JsonSelfSerializer} is generic).
 * There has to be a default constructor for the deserialization to work, and add a {@link JsonAdapter} annotation
 * since that's not inherited: <code>@JsonAdapter(JsonSelfSerializer.JsonSelfSerializerTypeAdapterFactory.class)</code>
 */
@JsonAdapter(JsonSelfSerializer.JsonSelfSerializerTypeAdapterFactory.class) // just here to copy it, not inherited.
public interface JsonSelfSerializer {

    /** Writes the attributes of the object as JSON. Default: throws UnsupportedOperationException. */
    default void toJson(@Nonnull JsonWriter out, @Nonnull Gson gson, TypeToken<?> type) throws IOException {
        throw new UnsupportedOperationException("Serialization not supported for " + this.getClass().getName());
    }

    /** Initializes the attributes of the object from JSON. Default: throws UnsupportedOperationException. */
    default void fromJson(@Nonnull JsonReader in, @Nonnull Gson gson, TypeToken<?> type) throws IOException {
        throw new UnsupportedOperationException("Deserialization not supported for " + this.getClass().getName());
    }

    /**
     * GSon Serializer / deserializer factory that calls the {@link #toJson(JsonWriter, Gson)} /
     * {@link #fromJson(JsonReader, Gson)} methods.
     */
    class JsonSelfSerializerTypeAdapterFactory implements TypeAdapterFactory {

        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (JsonSelfSerializer.class.isAssignableFrom(type.getRawType())) {
                return new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        JsonSelfSerializer object = (JsonSelfSerializer) value;
                        object.toJson(out, gson, type);
                    }

                    @Override
                    public T read(JsonReader in) throws IOException {
                        JsonSelfSerializer object;
                        try {
                            object = (JsonSelfSerializer) type.getRawType().getDeclaredConstructor().newInstance();
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                            throw new IOException("Could not create " + type, e);
                        }
                        object.fromJson(in, gson, type);
                        //noinspection unchecked
                        return (T) object;
                    }
                };
            }
            return null;
        }
    }

}

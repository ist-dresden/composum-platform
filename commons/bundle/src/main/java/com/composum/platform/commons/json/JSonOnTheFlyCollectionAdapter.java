package com.composum.platform.commons.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.reflect.TypeUtils;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * GSON TypeAdapterFactory to read / write collections that are not stored in memory but created on the fly during
 * serialization / are processed during deserialization without needing to store each element; for use (ONLY!) with
 * {@literal @}{@link com.google.gson.annotations.JsonAdapter}.
 * To serialize it requires the serialized object to implement {@link Iterable}, to deserialize it requires the
 * element to implement {@link java.util.function.Consumer}. If the element implements {@link java.io.Closeable} the
 * method {@link Closeable#close()} is called when everything is read - for some additional processing if that's needed.
 * It also works with a {@link java.util.Collection}, though that's not the intended usecase.
 * <p>CAUTION: this may not be used with {@link GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory)}
 * since we have no way to tell whether it's meant for a type or not. We did need, however, need to implement
 * TypeAdapterFactory since we need access to the Gson object and the JsonWriter / JsonReader.</p>
 */
public class JSonOnTheFlyCollectionAdapter implements TypeAdapterFactory {

    @Override
    @Nonnull
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (Iterable.class.isAssignableFrom(type.getRawType()) ||
                Consumer.class.isAssignableFrom(type.getRawType()) ||
                Collection.class.isAssignableFrom(type.getRawType())) {
            return new OnTheFlyAdapter<>(gson, type);
        }
        throw new IllegalArgumentException("Not Iterable, Consumer or Collection: " + type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected static class OnTheFlyAdapter<T> extends TypeAdapter<T> {
        private final Gson gson;
        private final TypeToken<T> type;

        public OnTheFlyAdapter(Gson gson, TypeToken<T> type) {
            this.gson = gson;
            this.type = type;
        }

        @SuppressWarnings("resource")
        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (value instanceof Iterable) {
                out.beginArray();
                Type elementType = TypeUtils.getTypeArguments(type.getType(), Iterable.class).values().iterator().next();
                for (Object object : (Iterable) value) {
                    gson.toJson(object, elementType, out);
                }
                out.endArray();
            } else if (value == null) {
                out.nullValue();
            } else {
                // thow up, not return null, so that it crashes easily when abused with
                // GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory) or is used accidentially with wrong
                // types.
                throw new IOException("Must be Iterable to be written with JSonOnTheFlyCollectionAdapter: " + value.getClass().getName());
            }
        }

        @Override
        @Nonnull
        public T read(JsonReader in) throws IOException {
            in.beginArray();
            T receiver;
            try {
                receiver = (T) type.getRawType().getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new IOException("Could not create " + type, e);
            }
            if (receiver instanceof Consumer) {
                Consumer consumer = (Consumer) receiver;
                Type elementType =
                        TypeUtils.getTypeArguments(type.getType(), Consumer.class).values().iterator().next();
                while (in.hasNext()) {
                    Object element = gson.fromJson(in, elementType);
                    consumer.accept(element);
                }
            } else if (receiver instanceof Collection) {
                Collection collection = (Collection) receiver;
                Type elementType =
                        TypeUtils.getTypeArguments(type.getType(), Collection.class).values().iterator().next();
                collection.clear();
                while (in.hasNext()) {
                    Object element = gson.fromJson(in, elementType);
                    collection.add(element);
                }
            } else {
                throw new IOException("Must be Consumer to be deserialized with " +
                        "JSonOnTheFlyCollectionAdapter: " + receiver.getClass().getName());
            }
            in.endArray();
            if (receiver instanceof Closeable) {
                ((Closeable) receiver).close();
            }
            return receiver;
        }
    }
}

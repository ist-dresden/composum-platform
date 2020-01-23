package com.composum.platform.commons.json;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * Reads a stream of objects from a {@link com.google.gson.stream.JsonReader} positioned into an array, as converted
 * with {@link Gson}. The {@link JsonReader} must be positioned at an array - the {@link JsonReader#beginArray()}
 * must not have been called. After the iteration, the array is closed with {@link JsonReader#endArray()}. One shot
 * only, of course.
 *
 * @param <T> the class of the object we want the JSON converted to.
 */
public class JsonArrayAsIterable<T> implements Iterable<T>, AutoCloseable {

    @Nonnull
    protected final JsonReader jsonReader;

    @Nonnull
    protected final Class<T> objectClass;

    @Nonnull
    protected final Gson gson;

    boolean consumed;
    boolean closed;

    /**
     * Reads an array of objects of the class {objectClass} from a {@link JsonReader}, converting the objects with
     * {@link Gson}. The {@link #iterator()} can be created only once. This calls {@link JsonReader#beginArray()}
     * immediately to check whether there really is an array.
     */
    public JsonArrayAsIterable(@Nonnull JsonReader jsonReader, @Nonnull Class<T> objectClass, @Nonnull Gson gson) throws IOException {
        this.jsonReader = jsonReader;
        this.objectClass = objectClass;
        this.gson = gson;
        jsonReader.beginArray();
    }

    /**
     * Returns an iterator for the objects - one time use only! This needs to be read completely, or the JSON reading
     * process will throw up.
     *
     * @throws IllegalStateException if it was called already.
     */
    @Override
    @Nonnull
    public Iterator<T> iterator() {
        if (consumed) { throw new IllegalStateException("Iterator was already consumed."); }
        consumed = true;
        return new JsonArrayAsIterator();
    }

    /** Does read the array to the end if it wasn't yet, and {@link JsonReader#endArray()}. */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            while (jsonReader.hasNext()) {
                jsonReader.skipValue();
            }
            jsonReader.endArray();
        }
    }

    /**
     * Turns the this into a stream - one time use only. Caution: {@link JsonReader#endArray()} is only called if
     * the stream is consumed completely - beware of shortcut methods. Or call {@link #close()} afterwards.
     */
    @Nonnull
    public Stream<T> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
    }

    /** Forwards the iterator methods to the {@link JsonReader} / {@link Gson} methods. */
    protected class JsonArrayAsIterator implements Iterator<T> {

        /** Checks whether there is a next, and {@link #close()}s the array if not. */
        @Override
        public boolean hasNext() {
            try {
                boolean result = jsonReader.hasNext();
                if (!result && !closed) {
                    close();
                }
                return result;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Returns the next object. */
        @Override
        @Nonnull
        public T next() {
            return requireNonNull(gson.fromJson(jsonReader, objectClass));
        }
    }
}

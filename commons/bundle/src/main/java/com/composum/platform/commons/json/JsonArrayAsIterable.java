package com.composum.platform.commons.json;

import com.composum.platform.commons.util.AutoCloseableIterator;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import org.apache.commons.lang3.StringUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * Reads a stream of objects from a {@link com.google.gson.stream.JsonReader} positioned into an array, as converted
 * with {@link Gson}. The {@link JsonReader} must be positioned before an array or field with the array when
 * {@link #iterator()} or {@link #stream()} is called - the {@link JsonReader#beginArray()} must not have been called
 * yet. After the iteration is complete, the array is automatically closed with {@link JsonReader#endArray()}.
 * If the iteration stops prematurely, it is neccesary to call {@link #close()}. One shot only, of course.
 *
 * @param <T> the class of the object we want the JSON converted to.
 */
public class JsonArrayAsIterable<T> implements Iterable<T>, AutoCloseable {

    @NotNull
    protected final JsonReader jsonReader;

    @NotNull
    protected final Class<T> objectClass;

    @NotNull
    protected final Gson gson;

    @Nullable
    protected final String fieldName;

    boolean wasOpened;
    boolean wasClosed;
    int numberRead;

    /**
     * Reads an array of objects of the class {objectClass} from a {@link JsonReader}, converting the objects with
     * {@link Gson}. The {@link #iterator()} can be created only once. The {@link JsonReader} is only consulted when
     * {@link #iterator()} or {@link #stream()} is called - it is only then expected to be positioned at the field /
     * array.
     *
     * @param fieldName if given, we expect to read this as the {@link JsonReader#nextName()} before the
     *                  {@link JsonReader#beginArray()}.
     */
    public JsonArrayAsIterable(@NotNull JsonReader jsonReader, @NotNull Class<T> objectClass, @NotNull Gson gson,
                               @Nullable String fieldName) {
        this.jsonReader = jsonReader;
        this.objectClass = objectClass;
        this.gson = gson;
        this.fieldName = fieldName;
    }

    /**
     * Returns an iterator for the objects - one time use only! This needs to be read completely, or the JSON reading
     * process will throw up. This reads the field name from the JsonParser and does a
     * {@link JsonReader#beginArray()} to check the syntax.
     *
     * @throws IllegalStateException if it was called already
     * @throws JsonParseException    if there was an {@link IOException} or if the JSON structure was wrong
     */
    @Override
    @NotNull
    public AutoCloseableIterator<T> iterator() throws IllegalStateException, JsonParseException {
        open();
        return new JsonArrayAsIterator();
    }


    /** Alternative to {@link #iterator()} that only accesses the JsonReader when the iterator is first called. */
    @NotNull
    public AutoCloseableIterator<T> delayedIterator() {
        // open is done later when the iterator is first called.
        return new JsonArrayDelayedIterator();
    }

    protected void open() throws JsonParseException, IllegalStateException {
        if (wasOpened) { throw new IllegalStateException("Iterator was already consumed."); }
        if (wasClosed) { throw new IllegalStateException("Iterable was already closed."); }
        try {
            wasOpened = true;
            if (StringUtils.isNotBlank(fieldName)) {
                String nextName = jsonReader.nextName();
                if (!fieldName.equals(nextName)) {
                    throw new JsonParseException("expected " + fieldName + " but got " + nextName);
                }
            }
            jsonReader.beginArray();
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }

    /** Skips through the data if it wasn't read yet and then close. Makes sure the JSON representation was read. */
    public void skipAndClose() {
        if (!wasOpened) { open(); }
        close();
    }

    /**
     * If the iterator was created, does read the array to the end if it wasn't yet, and {@link JsonReader#endArray()}
     * .
     */
    @Override
    public void close() throws JsonIOException {
        if (wasOpened && !wasClosed) {
            try {
                while (jsonReader.hasNext()) {
                    jsonReader.skipValue();
                }
                jsonReader.endArray();
            } catch (IOException e) {
                throw new JsonIOException(e);
            }
        }
        wasClosed = true;
    }

    /** The number of elements which were read from this. */
    public int getNumberRead() {
        return numberRead;
    }

    /**
     * Turns the this into a stream - one time use only. Caution: {@link JsonReader#endArray()} is only called if
     * the stream is consumed completely - beware of shortcut methods. Or call {@link #close()} afterwards.
     */
    @NotNull
    public Stream<T> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(),
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE),
                false);
    }

    /** Forwards the iterator methods to the {@link JsonReader} / {@link Gson} methods. */
    protected class JsonArrayAsIterator implements AutoCloseableIterator<T> {

        /** Checks whether there is a next, and {@link #close()}s the array if not. */
        @Override
        public boolean hasNext() throws JsonIOException {
            if (wasClosed) { throw new IllegalStateException("Iterable was already closed."); }
            try {
                boolean result = jsonReader.hasNext();
                if (!result) { close();}
                return result;
            } catch (IOException e) {
                throw new JsonIOException(e);
            }
        }

        /** Returns the next object. */
        @Override
        @NotNull
        public T next() {
            if (wasClosed) { throw new IllegalStateException("Iterable was already closed."); }
            T result = requireNonNull(gson.fromJson(jsonReader, objectClass));
            numberRead += 1;
            return result;
        }

        /** Reads the array until the end - forwards to {@link JsonArrayAsIterable#close()}. */
        @Override
        public void close() throws JsonIOException {
            JsonArrayAsIterable.this.close();
        }
    }

    /** Only opens the {@link JsonArrayAsIterable} when the iterator is first used. */
    protected class JsonArrayDelayedIterator implements AutoCloseableIterator<T> {

        private Iterator<T> iterator;

        @Override
        public void close() {
            JsonArrayAsIterable.this.close();
        }

        @NotNull
        protected Iterator<T> getIterator() {
            if (iterator == null) {
                iterator = JsonArrayAsIterable.this.iterator();
            }
            return iterator;
        }

        @Override
        public boolean hasNext() {
            return getIterator().hasNext();
        }

        @Nullable
        @Override
        public T next() {
            return getIterator().next();
        }
    }
}

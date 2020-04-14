package com.composum.platform.commons.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.stream.JsonWriter;
import org.apache.http.HttpEntity;
import org.apache.http.entity.AbstractHttpEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** An {@link HttpEntity} that serializes an object on the fly and writes it to the request. */
public class JsonHttpEntity<T> extends AbstractHttpEntity implements HttpEntity {

    @Nullable
    private final T object;

    @Nonnull
    private final Gson gson;

    /** @param object the object to serialize */
    public JsonHttpEntity(@Nullable T object, @Nullable Gson gson) {
        setContentType("application/json; charset=UTF-8");
        this.object = object;
        this.gson = gson != null ? gson : new GsonBuilder().create();
    }

    /** Delegates to {@link #writeTo(JsonWriter)}. */
    @Override
    public void writeTo(OutputStream outstream) throws IOException {
        try (Writer writer = new OutputStreamWriter(outstream, StandardCharsets.UTF_8);
             JsonWriter jsonWriter = new JsonWriter(writer)) {
            writeTo(jsonWriter);
        }
    }

    /** Uses gson to write the object; possible hook for special serialization mechanisms. */
    protected void writeTo(@Nonnull JsonWriter jsonWriter) throws IOException {
        if (object != null) {
            gson.toJson(object, object.getClass(), jsonWriter);
        } else {
            gson.toJson(JsonNull.INSTANCE, jsonWriter);
        }
    }

    @Override
    public boolean isRepeatable() {
        return true;
    }

    @Override
    public long getContentLength() {
        return -1;
    }

    @Override
    public boolean isStreaming() {
        return false;
    }

    /** Not implemented. */
    @Override
    public InputStream getContent() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("JsonHttpEntity only supports writeTo(OutputStream).");
    }

}

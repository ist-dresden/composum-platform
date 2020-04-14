package com.composum.platform.commons.json;

import org.apache.http.StatusLine;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marker interface that marks an interface as implemented with {@link AbstractJsonRpcClient} and
 * {@link AbstractJsonRpcServlet}.
 */
public interface JsonRpcInterface {

    /** Exception that says something went wrong with the remote call. */
    class JsonRpcException extends RuntimeException {

        @Nullable
        protected StatusLine statusLine;

        public JsonRpcException(@Nonnull String message, @Nullable StatusLine statusLine, @Nullable Throwable cause) {
            super(message, cause);
            this.statusLine = statusLine;
        }
    }

}

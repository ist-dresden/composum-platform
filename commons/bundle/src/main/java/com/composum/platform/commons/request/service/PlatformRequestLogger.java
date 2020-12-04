package com.composum.platform.commons.request.service;

import com.composum.platform.commons.request.AccessMode;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletRequest;
import java.io.Serializable;

public interface PlatformRequestLogger {

    String RA_REQUEST_TRACKING_DATA = PlatformRequestLogger.class.getName() + "#data";

    class Data implements Serializable {

        protected final long requestStartTimeMs;
        public Long requestEndTimeMs;

        protected Data() {
            requestStartTimeMs = System.currentTimeMillis();
        }

        public long getRequestStartTimeMs() {
            return requestStartTimeMs;
        }

        public long getRequestEndTimeMs() {
            if (requestEndTimeMs == null) {
                requestEndTimeMs = System.currentTimeMillis();
            }
            return requestEndTimeMs;
        }
    }

    static Data data(ServletRequest request) {
        Data data = (Data) request.getAttribute(RA_REQUEST_TRACKING_DATA);
        if (data == null) {
            data = new Data();
            request.setAttribute(RA_REQUEST_TRACKING_DATA, data);
        }
        return data;
    }

    /**
     * @return an identifier for the logger implementation
     */
    String getName();

    /**
     * @return 'true' if the logger is able to handle the logging for the given request
     */
    boolean canHandle(@Nullable AccessMode accessMode,
                      @Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response);

    /**
     * does the logging for the given request
     */
    void logRequest(@Nullable AccessMode accessMode,
                    @Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response);
}

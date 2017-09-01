package com.composum.platform.commons.response;

import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * a wrapper to drop the normal output (for debug views with different output)
 */
public class DropResponseWrapper extends SlingHttpServletResponseWrapper {

    protected static final Logger LOG = LoggerFactory.getLogger(DropResponseWrapper.class);

    private final ServletOutputStream trash;
    private final PrintWriter writer;

    public DropResponseWrapper(SlingHttpServletResponse wrappedResponse) {
        super(wrappedResponse);
        trash = new ServletOutputStream() {

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) throws IOException {
            }
        };
        writer = new PrintWriter(new OutputStreamWriter(trash));
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return trash;
    }

    @Override
    public void flushBuffer() {
    }

    @Override
    public void setStatus(int status) {
    }

    @Override
    public void setContentType(String type) {
    }
}

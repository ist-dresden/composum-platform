package com.composum.platform.commons.util;

import com.composum.sling.clientlibs.handle.FileHandle;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;

/**
 * Presents a {@link com.composum.sling.clientlibs.handle.FileHandle} as a javax.activation.{@link DataSource}, primarily for reading it.
 */
public class FileHandleDataSource implements DataSource {

    @Nonnull
    protected final FileHandle fileHandle;

    /**
     * Creates a {@link com.composum.sling.clientlibs.handle.FileHandle} as a javax.activation.{@link DataSource}, primarily for reading it.
     */
    public FileHandleDataSource(@Nonnull FileHandle fileHandle) {
        this.fileHandle = fileHandle;
    }

    @Override
    @Nullable
    public InputStream getInputStream() throws IOException {
        return fileHandle.getStream();
    }

    /**
     * This sets the content on {@link OutputStream#close()}, not before. Caution: this stores everything in memory first.
     */
    @Override
    @Nonnull
    public OutputStream getOutputStream() throws IOException {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                fileHandle.storeContent(new ByteArrayInputStream(toByteArray()));
            }
        };
    }

    @Override
    public String getContentType() {
        return fileHandle.getMimeType();
    }

    @Override
    public String getName() {
        return fileHandle.getName();
    }
}

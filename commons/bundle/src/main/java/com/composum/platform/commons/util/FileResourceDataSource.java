package com.composum.platform.commons.util;

import com.composum.sling.clientlibs.handle.FileHandle;
import org.apache.sling.api.resource.Resource;

import javax.activation.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.*;

/**
 * Presents a {@link com.composum.sling.clientlibs.handle.FileHandle} as a javax.activation.{@link DataSource}, primarily for reading it.
 */
public class FileResourceDataSource implements DataSource {

    @NotNull
    protected final FileHandle fileHandle;

    /**
     * Creates a {@link com.composum.sling.clientlibs.handle.FileHandle} as a javax.activation.{@link DataSource}, primarily for reading it.
     */
    public FileResourceDataSource(@NotNull FileHandle fileHandle) {
        this.fileHandle = fileHandle;
    }

    /**
     * Wraps a file resource as a javax.activation.{@link DataSource}, primarily for reading it.
     */
    public FileResourceDataSource(@NotNull Resource fileResource) {
        this.fileHandle = new FileHandle(fileResource);
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
    @NotNull
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

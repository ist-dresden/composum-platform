package com.composum.platform.commons.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * An {@link InputStream} that wraps around another input stream, actually creating it only once it's actually used.
 * All stream functions check whether the stream to wrap was already created, create it if necessary (throwing an
 * IOException if that fails), and then are forwarded to the wrapped stream. If the creation fails, it's exception
 * is thrown whenever the stream is accessed - except in close.
 */
public class LazyInputStream extends InputStream {

    private static final Logger LOG = LoggerFactory.getLogger(LazyInputStream.class);

    protected volatile Callable<InputStream> inputCreator;

    protected volatile InputStream in;

    protected volatile Exception accessProhibitedReason;

    protected final Object lockObject = new Object();

    /**
     * Initializes the lazy stream with a callable creating the wrapped stream at first use.
     *
     * @param inputStreamCreator is called at most once to create the stream.
     */
    public LazyInputStream(@NotNull Callable<InputStream> inputStreamCreator) {
        this.inputCreator = Objects.requireNonNull(inputStreamCreator);
    }

    @NotNull
    protected InputStream giveWrappedStream() throws IOException {
        if (accessProhibitedReason != null) {
            throw new IllegalStateException("Stream is in failed state", accessProhibitedReason);
        }
        InputStream incopy = in; // copy first since it's volatile and can be reset by close.
        if (incopy != null) { return incopy; }
        synchronized (lockObject) { // try to create the stream.
            if (accessProhibitedReason != null) {
                throw new IllegalStateException("Stream is in failed state", accessProhibitedReason);
            }
            if (in == null) {
                try {
                    Callable<InputStream> creatorCopy = inputCreator;
                    inputCreator = null; // allow one call invocation only.
                    in = creatorCopy.call();
                    LOG.debug("wrapped stream created of @{}", System.identityHashCode(this));
                    if (in == null) {
                        accessProhibitedReason = new IOException("Stream creator returned null!");
                        throw accessProhibitedReason;
                    }
                } catch (IOException e) {
                    LOG.warn("Delayed stream creation failed", e);
                    accessProhibitedReason = e;
                    throw e;
                } catch (Exception e) {
                    LOG.warn("Delayed stream creation failed", e);
                    accessProhibitedReason = e;
                    throw new IOException("Delayed stream creation failed", e);
                }
            }
            return in;
        }
    }

    @Override
    public void close() throws IOException {
        if (in != null) {
            synchronized (lockObject) {
                inputCreator = null; // avoid accidentially recreating it later.
                InputStream inCopy = in;
                in = null;
                if (inCopy != null) {
                    try {
                        LOG.debug("closing stream of @{}", System.identityHashCode(this));
                        inCopy.close();
                        accessProhibitedReason = new IllegalStateException("Stream was already closed.");
                    } catch (IOException e) {
                        accessProhibitedReason = e;
                        throw e;
                    } catch (Exception e) {
                        accessProhibitedReason = e;
                        throw new IOException("Exception when closing stream", e);
                    }
                }
            }
        }
        inputCreator = null; // avoid accidentially recreating it later.
    }

    // ================= now just forwarding to wrapped stream ==================

    @Override
    public int read() throws IOException {
        return giveWrappedStream().read();
    }

    @Override
    public int read(@NotNull byte[] b, int off, int len) throws IOException {
        return giveWrappedStream().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return giveWrappedStream().skip(n);
    }

    @Override
    public int available() throws IOException {
        return giveWrappedStream().available();
    }

}

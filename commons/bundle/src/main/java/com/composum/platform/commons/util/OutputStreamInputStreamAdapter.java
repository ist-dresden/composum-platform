package com.composum.platform.commons.util;

import org.apache.sling.commons.threads.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Creates an {@link java.io.InputStream} from a callable that can write data to an {@link java.io.OutputStream}
 * only. You can use this whenever you need to serve an API that needs an InputStream to read from, but the natural
 * code for generating the data writes to an OutputStream (such as ZipOutputStream) and you don't want to store
 * everything into a ByteArrayOutputStream in memory, or something. Caution: the code writing to the OutputStream
 * is run in a parallel Thread.
 */
public class OutputStreamInputStreamAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(OutputStreamInputStreamAdapter.class);

    protected final ExceptionThrowingConsumer<OutputStream, IOException> writeToOutputStream;
    protected final ExecutorService executor;
    protected final ThreadPool threadPool;

    protected volatile Exception exception;
    protected volatile Future<?> execution;

    /**
     * Returns an input stream that passes out the contents that {writeToOutputStream} writes.
     * {writeToOutputStream} runs in a separate thread - if there was an exception we try to throw it
     * on the next call to this inputstream.
     *
     * @param writeToOutputStream a function that writes some contents to an OutputStream when called. We call this
     *                            only when something reads from the InputStream. Caution: this is run in another
     *                            thread, so it needs to be threadsafe.
     * @param executor            an {@link ExecutorService} which is used to execute {writeToOutputStream}
     * @return the stream
     */
    @NotNull
    public static InputStream of(@NotNull ExceptionThrowingConsumer<OutputStream, IOException> writeToOutputStream,
                                 @NotNull ExecutorService executor) {
        return new OutputStreamInputStreamAdapter(writeToOutputStream, executor, null).getInputStream();
    }

    /**
     * Returns an input stream that passes out the contents that {writeToOutputStream} writes.
     * {writeToOutputStream} runs in a separate thread - if there was an exception we try to throw it
     * on the next call to this inputstream.
     *
     * @param writeToOutputStream a function that writes some contents to an OutputStream when called. We call this
     *                            only when something reads from the InputStream. Caution: this is run in another
     *                            thread, so it needs to be threadsafe.
     * @param threadPool          an {@link ThreadPool} which is used to execute {writeToOutputStream}
     * @return the stream
     */
    @NotNull
    public static InputStream of(@NotNull ExceptionThrowingConsumer<OutputStream, IOException> writeToOutputStream,
                                 @NotNull ThreadPool threadPool) {
        return new OutputStreamInputStreamAdapter(writeToOutputStream, null, threadPool).getInputStream();
    }

    protected OutputStreamInputStreamAdapter(@NotNull ExceptionThrowingConsumer<OutputStream, IOException> writeToOutputStream,
                                             @Nullable ExecutorService executor, @Nullable ThreadPool threadPool) {
        this.writeToOutputStream = Objects.requireNonNull(writeToOutputStream);
        this.threadPool = threadPool;
        this.executor = executor;
        if (threadPool == null && executor == null) {
            throw new IllegalArgumentException("We need either an ExecutorService or a ThreadPool");
        }
    }

    protected InputStream createPipedStream() throws IOException {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream outputStream = new PipedOutputStream(input);
        Runnable runnable = () -> {
            try {
                LOG.debug("Start writing");
                writeToOutputStream.apply(outputStream);
            } catch (Exception e) {
                exception = e;
                LOG.warn("Writing to output stream failed: " + e, e);
            } finally {
                try {
                    LOG.debug("Closing stream");
                    outputStream.close();
                } catch (Exception e) {
                    if (exception == null) {
                        exception = e;
                    } else {
                        exception.addSuppressed(e);
                    }
                    LOG.warn("Closing output stream failed: " + e, e);
                }
            }
        };
        execution = executor != null ? executor.submit(runnable) : threadPool.submit(runnable);
        return input;
    }

    /**
     * If the writing to the output stream had an exception, we throw it here.
     */
    protected void possiblyThrow() throws IOException {
        if (exception != null) {
            LOG.debug("Throwing exception that occurred during writing (see warning) - {}", exception.toString());
            throw new IOException("Trouble writing stream: " + exception, exception);
        }
    }

    /**
     * Returns an input stream that passes out the contents that {@link #writeToOutputStream} writes.
     * {@link #writeToOutputStream} runs in a separate thread - if there was an exception we try to throw it
     * on the next call to this inputstream.
     */
    @NotNull
    public InputStream getInputStream() {
        return new LazyInputStream(this::createPipedStream) {

            @Override
            public int read() throws IOException {
                possiblyThrow();
                int read = super.read();
                possiblyThrow();
                return read;
            }

            @Override
            public int read(@NotNull byte[] b, int off, int len) throws IOException {
                possiblyThrow();
                int read = super.read(b, off, len);
                possiblyThrow();
                return read;
            }

            @Override
            public void close() throws IOException {
                LOG.debug("Closing inputstream");
                try {
                    possiblyThrow();
                } finally {
                    exception = null; // don't throw it again accidentially through the mechanics
                    if (execution != null) {
                        execution.cancel(true);
                    }
                    super.close();
                }
            }

            @Override
            public long skip(long n) throws IOException {
                possiblyThrow();
                long skip = giveWrappedStream().skip(n);
                possiblyThrow();
                return skip;
            }

            @Override
            public int available() throws IOException {
                possiblyThrow();
                int available = giveWrappedStream().available();
                possiblyThrow();
                return available;
            }

        };
    }

}

package com.composum.platform.commons.util;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Tests {@link OutputStreamInputStreamAdapter}.
 */
public class OutputStreamInputStreamAdapterTest {

    private static final Logger LOG = LoggerFactory.getLogger(OutputStreamInputStreamAdapterTest.class);

    private ExecutorService executor;

    @Before
    public void setup() {
        executor = Executors.newFixedThreadPool(2);
    }

    @After
    public void shutdown() {
        executor.shutdownNow();
    }

    @Test
    public void success() throws IOException {
        String content = RandomStringUtils.randomAlphanumeric(10000);
        try (InputStream stream = OutputStreamInputStreamAdapter.of(
                (OutputStream out) -> out.write(content.getBytes()),
                executor);
             BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        ) {
            assertEquals(content, in.readLine());
        }
    }

    @Test
    public void throwsException() throws Exception {
        System.out.flush();
        System.err.flush();
        Thread.sleep(100); // make sure log messages are written to deconfuse output
        LOG.debug("Start throwsException");
        try (
                InputStream stream = OutputStreamInputStreamAdapter.of(
                        (OutputStream out) -> {
                            throw new RuntimeException("Throws immediately before writing anything");
                        },
                        executor);
                BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        ) {
            in.readLine();
        } catch (IOException e) {
            LOG.info("OK: received {}", e.toString());
        } catch (Throwable t) {
            LOG.error("Unexpected exception", t);
            throw t;
        } finally {
            LOG.debug("End throwsException");
            System.out.flush();
            System.err.flush();
            Thread.sleep(100); // make sure log messages are written to deconfuse output
        }
    }

    @Test
    public void writeThenException() throws IOException {
        InputStream stream = OutputStreamInputStreamAdapter.of(
                (OutputStream out) -> {
                    out.write("abcd".getBytes());
                    out.flush();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    throw new RuntimeException("exception after writing 4 bytes");
                },
                executor);
        byte[] bytes = new byte[4];
        assertEquals(4, stream.read(bytes));
        assertEquals("abcd", new String(bytes));
        try {
            stream.read(bytes);
            fail("Expected IOException");
        } catch (IOException e) {
            // OK, expected
        }
    }

    boolean interrupted = false;

    @Test
    public void writeThenExceptionWithoutWait() throws IOException, InterruptedException {
        try (
                InputStream stream = OutputStreamInputStreamAdapter.of(
                        (OutputStream out) -> {
                            out.write("abcd".getBytes());
                            out.flush();
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                interrupted = true;
                                throw new RuntimeException(e);
                            }
                            throw new RuntimeException("not interrupted");
                        },
                        executor)
        ) {
            byte[] bytes = new byte[4];
            assertEquals(4, stream.read(bytes));
            assertEquals("abcd", new String(bytes));
            // we don't read to the end of the stream
        }
        // make sure the cancel call did his work, so that the writer isn't run indefinitely.
        Thread.sleep(100);
        assertTrue(interrupted);
    }

    Exception writeException;
    boolean runtofinish = false;

    @Test
    public void writeMuchReadLittle() throws Exception {
        try (
                InputStream stream = OutputStreamInputStreamAdapter.of(
                        (OutputStream out) -> {
                            out.write("abcd".getBytes());
                            try {
                                for (int i = 0; i < 100; ++i) {
                                    out.write(RandomStringUtils.randomAlphanumeric(1024).getBytes());
                                }
                            } catch (Exception e) {
                                writeException = e;
                                throw e;
                            }
                            runtofinish = true;
                        },
                        executor)
        ) {
            byte[] bytes = new byte[4];
            assertEquals(4, stream.read(bytes));
            assertEquals("abcd", new String(bytes));
            // we don't read to the end of the stream
        }
        // make sure the cancel in close() did his work, so that the writer isn't run indefinitely.
        Thread.sleep(100);
        assertFalse(runtofinish);
        assertNotNull(writeException);
    }

}

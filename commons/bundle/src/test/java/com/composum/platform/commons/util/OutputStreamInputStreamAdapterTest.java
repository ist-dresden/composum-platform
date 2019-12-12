package com.composum.platform.commons.util;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests {@link OutputStreamInputStreamAdapter}. */
public class OutputStreamInputStreamAdapterTest {

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

    @Test(expected = IOException.class)
    public void throwsException() throws IOException {
        try (
                InputStream stream = OutputStreamInputStreamAdapter.of(
                        (OutputStream out) -> { throw new RuntimeException("go away"); },
                        executor);
                BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        ) {
            in.readLine();
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
                    throw new RuntimeException("go away");
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
                            throw new RuntimeException("go away");
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

package com.composum.platform.commons.util;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Test for {@link LazyInputStream}. */
public class LazyInputStreamTest {

    @Test
    public void successful() throws IOException {
        String content = "Hallo, this is a test stream";
        LazyInputStream stream = new LazyInputStream(() -> new ByteArrayInputStream(content.getBytes()));
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream))) {
            assertEquals(content, bufferedReader.readLine());
        }
    }

    @Test
    public void throwOnAccess() throws IOException {
        LazyInputStream stream = new LazyInputStream(() -> { throw new RuntimeException("failed.");});
        // since we are here the creator was not called yet
        try {
            stream.read();
            Assert.fail("Exception expected");
        } catch (IOException e) {
            // OK.
        }
    }

    @Test
    public void openedAndClosed() throws IOException {
        InputStream mock = mock(InputStream.class);
        when(mock.read()).thenReturn(42);
        try (LazyInputStream stream = new LazyInputStream(() -> mock)) {
            assertEquals(42, stream.read());
            stream.close();
        }
        verify(mock, times(1)).close();
    }

}

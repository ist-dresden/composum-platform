package com.composum.platform.commons.response;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * a buffer to catch the response output
 */
public class ServletOutputBuffer extends ServletOutputStream {

	protected final ByteArrayOutputStream buffer;

	public ServletOutputBuffer() {
		super();
		buffer = new ByteArrayOutputStream();
	}

	@Override
	public boolean isReady() {
		return true;
	}

	@Override
	public void setWriteListener(WriteListener writeListener) {
	}

	@Override
	public void write(int b) throws IOException {
		this.buffer.write(b);
	}

	@Override
	public void flush() throws IOException {
		this.buffer.flush();
	}

	public String toString(Charset charset) throws UnsupportedEncodingException {
		return buffer.toString(charset.name());
	}
}

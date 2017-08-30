package com.composum.platform.commons.response;

import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * a wrapper to buffer the response output
 */
public class TextBufferResponseWrapper extends SlingHttpServletResponseWrapper {

	protected static final Logger LOG = LoggerFactory.getLogger(TextBufferResponseWrapper.class);

	public static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

	private final Charset charset;
	private final ServletOutputBuffer buffer;
	private final PrintWriter writer;

	public TextBufferResponseWrapper(SlingHttpServletResponse wrappedResponse) {
		this(wrappedResponse, DEFAULT_CHARSET);
	}

	public TextBufferResponseWrapper(SlingHttpServletResponse wrappedResponse, Charset charset) {
		super(wrappedResponse);
		this.charset = charset;
		buffer = new ServletOutputBuffer();
		writer = new PrintWriter(new OutputStreamWriter(buffer, charset));
	}

	@Override
	public String toString() {
		writer.flush();
		try {
			return buffer.toString(charset);
		}
		catch (UnsupportedEncodingException ex) {
			LOG.error(ex.getMessage(), ex);
			return buffer.toString();
		}
	}

	@Override
	public PrintWriter getWriter() {
		return writer;
	}

	@Override
	public ServletOutputStream getOutputStream() {
		return buffer;
	}

	@Override
	public void flushBuffer() {
		writer.flush();
	}
}

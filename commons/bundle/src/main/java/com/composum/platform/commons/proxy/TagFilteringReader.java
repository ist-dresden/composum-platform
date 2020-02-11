package com.composum.platform.commons.proxy;

import javax.annotation.Nonnull;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

public class TagFilteringReader extends FilterReader {

    public static final String[] DEFAULT_TO_STRIP = new String[]{"html", "body"};
    public static final String[] DEFAULT_TO_DROP = new String[]{"head", "style", "script"};

    public static final List<String> PROBABLY_NOT_CLOSED = Collections.singletonList("input");

    private enum Debug {none, read, strip, drop}

    private Debug debug = Debug.none;

    interface TagFilter {

        String tagName();

        int start(int token) throws IOException;

        int body() throws IOException;

        void debug(Character type);
    }

    protected abstract class BaseTagFilter implements TagFilter {

        protected final String tagName;

        protected BaseTagFilter(String tagName) {
            this.tagName = tagName;
        }

        @Override
        public String tagName() {
            return tagName;
        }

        @Override
        public int start(int token) throws IOException {
            Integer last = null;
            while (token != '>') {
                last = token;
                token = scan();
            }
            if (last != null && last == '/') {
                // tag ends at the end of start; no body
                openTags.pop();
                debug('#');
            } else {
                debug(null);
            }
            return read();
        }
    }

    protected class StripTagFilter extends BaseTagFilter {

        protected StripTagFilter(String tagName) {
            super(tagName);
        }

        @Override
        public int body() throws IOException {
            int token = scan();
            if (debug == Debug.strip) {
                System.out.println((char) token);
            }
            return token;
        }

        @Override
        public void debug(Character type) {
            if (debug == Debug.strip) {
                System.out.println("<" + (type != null ? type : "") + tagName + ">");
            }
        }
    }

    protected class DropTagFilter extends BaseTagFilter {

        protected DropTagFilter(String tagName) {
            super(tagName);
        }

        @Override
        public int body() throws IOException {
            int token;
            while ((token = scan()) >= 0 && openTags.peek() == this) {
                if (debug == Debug.drop) {
                    System.err.println((char) token);
                }
            }
            return token;
        }

        @Override
        public void debug(Character type) {
            if (debug == Debug.drop) {
                System.out.println("[" + (type != null ? type : "") + tagName + "]");
            }
        }
    }

    protected final List<String> toStrip;
    protected final List<String> toDrop;
    protected Stack<TagFilter> openTags = new Stack<>();

    protected char[] buffer = null;
    protected int bufferPos;

    public TagFilteringReader(@Nonnull final InputStream in) {
        this(new InputStreamReader(in, StandardCharsets.UTF_8), DEFAULT_TO_STRIP, DEFAULT_TO_DROP);
    }

    public TagFilteringReader(@Nonnull final InputStream in,
                              @Nonnull final String[] toStrip, @Nonnull String[] toDrop) {
        this(new InputStreamReader(in, StandardCharsets.UTF_8), toStrip, toDrop);
    }

    public TagFilteringReader(@Nonnull final Reader in) {
        this(in, DEFAULT_TO_STRIP, DEFAULT_TO_DROP);
    }

    public TagFilteringReader(@Nonnull final Reader in,
                              @Nonnull final String[] toStrip, @Nonnull final String[] toDrop) {
        super(in);
        this.toStrip = Arrays.asList(toStrip);
        this.toDrop = Arrays.asList(toDrop);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        int count;
        for (count = 0; count < len; count++) {
            int token = read();
            if (token < 0) {
                return count > 0 ? count : -1;
            }
            cbuf[off + count] = (char) token;
        }
        return count;
    }

    @Override
    public int read() throws IOException {
        int token;
        if (openTags.isEmpty()) {
            token = scan();
        } else {
            token = openTags.peek().body();
        }
        return token;
    }

    protected int scan() throws IOException {
        int token;
        if (buffer != null) {
            token = buffer[bufferPos];
            bufferPos++;
            if (bufferPos >= buffer.length) {
                buffer = null;
            }
        } else {
            token = next();
            if (token >= 0) {
                if (token == '<') {
                    if ((token = next()) >= 0) {
                        StringBuilder tagName = new StringBuilder();
                        if (token == '/') {
                            // tag end...
                            while ((token = next()) >= 0) {
                                if (token == '>') {
                                    TagFilter filter;
                                    if (!openTags.isEmpty() &&
                                            (filter = openTags.peek()).tagName().equals(tagName.toString().toLowerCase())) {
                                        filter.debug('/');
                                        openTags.pop();
                                        return read();
                                    } else {
                                        tagName.insert(0, '/');
                                        tagName.append((char) token);
                                        return buffer(tagName);
                                    }
                                } else {
                                    tagName.append((char) token);
                                }
                            }
                            tagName.insert(0, '/');
                        } else {
                            // tag start...
                            do {
                                if (token == ' ' || token == '>' || token == '/') {
                                    TagFilter filter = getFilter(tagName.toString().toLowerCase());
                                    if (filter != null) {
                                        openTags.push(filter);
                                        return filter.start(token);
                                    } else {
                                        tagName.append((char) token);
                                        return buffer(tagName);
                                    }
                                } else {
                                    tagName.append((char) token);
                                }
                            } while ((token = next()) >= 0);
                        }
                        return buffer(tagName);
                    } else {
                        return '<';
                    }
                }
            }
        }
        return token;
    }

    protected int next() throws IOException {
        int token = in.read();
        if (debug == Debug.read) {
            System.out.print((char) token);
        }
        return token;
    }

    protected int buffer(StringBuilder tagName) {
        buffer = tagName.toString().toCharArray();
        bufferPos = 0;
        return '<';
    }

    protected TagFilter getFilter(String tagName) {
        TagFilter current = openTags.isEmpty() ? null : openTags.peek();
        if (current instanceof DropTagFilter) {
            return new DropTagFilter(tagName);
        } else {
            if (toStrip.contains(tagName)) {
                return new StripTagFilter(tagName);
            } else if (toDrop.contains(tagName)) {
                return new DropTagFilter(tagName);
            }
        }
        return null;
    }
}

package com.composum.platform.models.htl;

import javax.servlet.jsp.JspWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * An adapter of a {@link java.io.PrintWriter} as a simulated {@link javax.servlet.jsp.JspWriter}.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
class JspWriterPrintWriterWrapper extends JspWriter {

    private final PrintWriter writer;

    public JspWriterPrintWriterWrapper(PrintWriter writer) {
        super(1, false);
        this.writer = writer;
    }

    @Override
    public void flush() {
        writer.flush();
    }

    @Override
    public void close() {
        writer.close();
    }

    @Override
    public int getRemaining() {
        return 0;
    }

    @Override
    public void write(int c) {
        writer.write(c);
    }

    @Override
    public void write(char[] buf, int off, int len) {
        writer.write(buf, off, len);
    }

    @Override
    public void write(char[] buf) {
        writer.write(buf);
    }

    @Override
    public void write(String s, int off, int len) {
        writer.write(s, off, len);
    }

    @Override
    public void write(String s) {
        writer.write(s);
    }

    @Override
    public void newLine() throws IOException {
        writer.println();
    }

    @Override
    public void print(boolean b) {
        writer.print(b);
    }

    @Override
    public void print(char c) {
        writer.print(c);
    }

    @Override
    public void print(int i) {
        writer.print(i);
    }

    @Override
    public void print(long l) {
        writer.print(l);
    }

    @Override
    public void print(float f) {
        writer.print(f);
    }

    @Override
    public void print(double d) {
        writer.print(d);
    }

    @Override
    public void print(char[] s) {
        writer.print(s);
    }

    @Override
    public void print(String s) {
        writer.print(s);
    }

    @Override
    public void print(Object obj) {
        writer.print(obj);
    }

    @Override
    public void println() {
        writer.println();
    }

    @Override
    public void println(boolean x) {
        writer.println(x);
    }

    @Override
    public void println(char x) {
        writer.println(x);
    }

    @Override
    public void println(int x) {
        writer.println(x);
    }

    @Override
    public void println(long x) {
        writer.println(x);
    }

    @Override
    public void println(float x) {
        writer.println(x);
    }

    @Override
    public void println(double x) {
        writer.println(x);
    }

    @Override
    public void println(char[] x) {
        writer.println(x);
    }

    @Override
    public void println(String x) {
        writer.println(x);
    }

    @Override
    public void println(Object x) {
        writer.println(x);
    }

    @Override
    public void clear() throws IOException {
        throw new IOException("Cannot clear a " + getClass());
    }

    @Override
    public void clearBuffer() throws IOException {
    }

    @Override
    public PrintWriter append(CharSequence csq) {
        return writer.append(csq);
    }

    @Override
    public PrintWriter append(CharSequence csq, int start, int end) {
        return writer.append(csq, start, end);
    }

    @Override
    public PrintWriter append(char c) {
        return writer.append(c);
    }
}

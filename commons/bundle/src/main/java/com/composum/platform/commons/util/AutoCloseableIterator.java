package com.composum.platform.commons.util;

import java.util.Iterator;

/** An {@link Iterator} which is also {@link AutoCloseable}/ */
public interface AutoCloseableIterator<T> extends Iterator<T>, AutoCloseable {

    // nothing new here.

}

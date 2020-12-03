/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.composum.platform.commons.util;

import org.apache.sling.engine.RequestLog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * The <code>FileRequestLog</code> class extracted from the default Sling FileRequestLog
 */
public class FileRequestLog implements RequestLog {

    // The map of shared open files (actually PrintWriter instances)
    private static Map<String, PrintWriter> logFiles = new HashMap<String, PrintWriter>();

    // Dispose class by closing all open PrintWeiter instances
    static void dispose() {
        for (final Writer w : logFiles.values()) {
            try {
                w.close();
            } catch (IOException ioe) {
                // don't care
            }
        }
        logFiles.clear();
    }

    // The PrintWriter used by this instance to write the messages
    private PrintWriter output;

    public FileRequestLog(File logFile) throws IOException {
        synchronized (logFiles) {
            final String fileName = logFile.getAbsolutePath();
            this.output = logFiles.get(fileName);
            if (this.output == null) {
                logFile.getParentFile().mkdirs();
                FileWriter fw = new FileWriter(logFile, true);
                this.output = new PrintWriter(fw);
                logFiles.put(fileName, this.output);
            }
        }
    }

    /**
     * @see RequestLog#write(String)
     */
    public void write(String message) {
        // use a local copy of the reference to not encounter NPE when this
        // log happens to be closed asynchronously while at the same time not
        // requiring synchronization
        PrintWriter writer = this.output;
        if (writer != null) {
            synchronized (writer) {
                writer.println(message);
                writer.flush();
            }
        }
    }

    public void close() {
        // just drop the reference to the output
        this.output = null;
    }
}

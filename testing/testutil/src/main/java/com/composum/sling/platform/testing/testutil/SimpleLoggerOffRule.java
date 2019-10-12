package com.composum.sling.platform.testing.testutil;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LocationAwareLogger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A JUnit test rule that switches off or raise the level of loggers for the duration of the test, to remove
 * expected and annoying log messages.
 */
public class SimpleLoggerOffRule extends TestWatcher {

    /** Field name for the loglevel in slf4j SimpleLogger. */
    private static final String FIELD_LOGLEVEL = "currentLogLevel";

    protected final IdentityHashMap<Logger, Object> originalLevels = new IdentityHashMap<>();

    /** The value for {@value #FIELD_LOGLEVEL} is used to switch the logger off. */
    protected int offValue;

    /**
     * Switches off the loggers for the given classes for the duration of each test. Assumes they are slf4j
     * SimpleLoggers.
     */
    public SimpleLoggerOffRule(Class<?>... loggerclasses) {
        for (Class<?> loggerclass : loggerclasses) {
            Logger logger = LoggerFactory.getLogger(loggerclass);
            try {
                Object currentLevel = FieldUtils.readField(logger, FIELD_LOGLEVEL, true);
                originalLevels.put(logger, currentLevel);
            } catch (IllegalAccessException e) {
                throw new AssertionError("Could not set level of logger with class " + logger.getClass());
            }
        }
    }

    @Override
    protected void starting(Description description) {
        super.starting(description);
        for (Map.Entry<Logger, Object> loggerObjectEntry : originalLevels.entrySet()) {
            try {
                offValue = 9999;
                FieldUtils.writeField(loggerObjectEntry.getKey(), FIELD_LOGLEVEL, offValue, true);
            } catch (IllegalAccessException e) {
                throw new AssertionError("Could not set level of logger with class " + loggerObjectEntry.getKey().getClass());
            }
        }
    }

    /** Allows everything from level INFO onwards, instead of OFF. @return this (builder pattern) */
    public SimpleLoggerOffRule infoEnabled() {
        offValue = LocationAwareLogger.INFO_INT;
        return this;
    }

    /** Allows everything from level WARN onwards, instead of OFF. @return this (builder pattern) */
    public SimpleLoggerOffRule warnEnabled() {
        offValue = LocationAwareLogger.WARN_INT;
        return this;
    }

    @Override
    protected void finished(Description description) {
        for (Map.Entry<Logger, Object> loggerObjectEntry : originalLevels.entrySet()) {
            try {
                FieldUtils.writeField(loggerObjectEntry.getKey(), FIELD_LOGLEVEL, loggerObjectEntry.getValue(), true);
            } catch (IllegalAccessException e) {
                throw new AssertionError("Could not set level of logger with class " + loggerObjectEntry.getKey().getClass());
            }
        }
        super.finished(description);
    }
}

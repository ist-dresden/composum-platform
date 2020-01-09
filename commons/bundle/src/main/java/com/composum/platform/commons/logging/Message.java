package com.composum.platform.commons.logging;

import org.slf4j.Logger;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A container for a message, e.g. about internal processes, that can be presented to the user. It could be localized,
 * and there might be details which can be suppressed, depending on the user / user settings or choices.
 */
public class Message {

    /** @see #getMessage() */
    protected String message;
    /** @see #getLevel() */
    protected Level level;
    /** @see #getCategory() */
    protected String category;
    /** @see #getArguments() */
    protected Object[] arguments;
    /** @see #getDetails() */
    protected List<Message> details;
    /** @see #getTimestamp() */
    protected Long timestamp;

    /** @deprecated only for JSON deserialization. */
    @Deprecated
    public Message() {
        // empty
    }

    /** Constructs a message. */
    public Message(@Nullable Level level, @Nonnull String message, Object... arguments) {
        this.message = message;
        this.level = level;
        this.category = category;
        this.arguments = arguments != null && arguments.length > 0 ? arguments : null;
        timestamp = System.currentTimeMillis();
    }

    /**
     * Adds a category: can optionally be used to categorize messages for filtering / sorting. This is not meant to
     * be shown directly to the user.
     *
     * @return this for builder style operation chaining.
     */
    @Nonnull
    public Message setCategory(@Nullable String category) {
        this.category = category;
        return this;
    }

    /**
     * Adds a detailmessage.
     *
     * @return this for builder style operation chaining.
     */
    @Nonnull
    public Message addDetail(@Nonnull Message detailMessage) {
        if (details == null) { details = new ArrayList<>(); }
        details.add(detailMessage);
        return this;
    }

    /** Time the message was created, as in {@link System#currentTimeMillis()}. */
    @Nullable
    public Long getTimestamp() {
        return timestamp;
    }

    /** Time the message was created. */
    @Nullable
    public Date getTimestampAsDate() {
        return timestamp != null ? new Date(timestamp) : null;
    }

    /**
     * The human readable message text, possibly with argument placeholders {@literal {}}. If i18n is wanted, this
     * is the key for the i18n - all variable parts should be put into the arguments. Mandatory part of a message.
     */
    @Nonnull
    public String getMessage() {
        return message;
    }

    /** The kind of message - informational, warning, error. Default is {@link Level#info}. */
    @Nonnull
    public Level getLevel() {
        return level != null ? level : Level.info;
    }

    /**
     * Can optionally be used to categorize messages for filtering / sorting. This is not meant to be shown directly
     * to the user.
     */
    public String getCategory() {
        return category;
    }

    /**
     * Optional arguments used in placeholders of the {@link #getMessage()}. If transmission over JSON is needed,
     * these must be serializable with GSON.
     */
    @Nonnull
    public List<Object> getArguments() {
        return arguments != null ? Arrays.asList(arguments) : Collections.emptyList();
    }

    /**
     * Optional unmodifiable collection of detail messages describing the problem further. To add details use {@link #addDetail(Message)}.
     *
     * @see #addDetail(Message)
     */
    @Nonnull
    public List<Message> getDetails() {
        return details != null ? Collections.unmodifiableList(details) : Collections.emptyList();
    }

    /**
     * Logs the message into the specified logger. Can be done automatically by a {@link MessageContainer} if
     * {@link MessageContainer#MessageContainer(Logger)} is used.
     *
     * @param log the log to write the message into
     * @return this message for builder-style operation-chaining.
     * @see MessageContainer#MessageContainer(Logger)
     */
    @Nonnull
    public Message logInto(@Nullable Logger log) {
        return logInto(log, null);
    }

    /**
     * Logs the message into the specified logger.
     *
     * @param log   the log to write the message into
     * @param cause optionally, an exception that is logged as a cause of the message
     * @return this message for builder-style operation-chaining.
     */
    @Nonnull
    public Message logInto(@Nonnull Logger log, @Nullable Throwable cause) {
        if (log != null) {
            switch (getLevel()) {
                case error:
                    if (log.isErrorEnabled()) {
                        log.error(toFormattedMessage(), cause);
                    }
                    break;
                case warn:
                    if (log.isWarnEnabled()) {
                        log.warn(toFormattedMessage(), cause);
                    }
                    break;
                case debug:
                    if (log.isDebugEnabled()) {
                        log.debug(toFormattedMessage(), cause);
                    }
                    break;
                case info:
                default:
                    if (log.isInfoEnabled()) {
                        log.info(toFormattedMessage(), cause);
                    }
                    break;
            }
        }
        return this;
    }

    /**
     * Return a full text representation of the message with replaced arguments and appended details. Mainly for
     * logging / debugging purposes.
     */
    public String toFormattedMessage() {
        StringBuilder buf = new StringBuilder();
        appendFormattedTo(buf, "");
        return buf.toString();
    }

    protected void appendFormattedTo(StringBuilder buf, String indent) {
        buf.append(indent);
        if (arguments != null) {
            FormattingTuple formatted = MessageFormatter.arrayFormat(message, arguments);
            buf.append(formatted.getMessage());
        } else {
            buf.append(message);
        }
        if (details != null) {
            String addIndent = indent + "    ";
            buf.append("\n").append(addIndent).append("Details:");
            for (Message detail : details) {
                buf.append("\n");
                detail.appendFormattedTo(buf, addIndent);
            }
        }
    }

    public enum Level {
        /**
         * Problems that require the users attention. This usually means that an operation was aborted or yielded
         * errorneous results.
         */
        error,
        /**
         * A warning that might or might not indicate that the result of an operation could have had errorneous
         * results.
         */
        warn,
        /** Informational messages for further details. */
        info,
        /**
         * Detailed informations that are not normally shown to users, but could help to investigate problems if
         * required.
         */
        debug
    }

}

package com.composum.platform.commons.logging;

import com.composum.sling.core.util.I18N;
import com.composum.sling.core.util.LoggerFormat;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(Message.class);

    /** @see #getLevel() */
    protected Level level;

    /** @see #getMessage() */
    protected String message;

    /** @see #getArguments() */
    protected Object[] arguments;

    /** @see #getCategory() */
    @Nullable
    protected String category;

    /** @see #getContext() */
    @Nullable
    protected String context;

    /** @see #getLabel() */
    @Nullable
    protected String label;

    /** @see #getDetails() */
    @Nullable
    protected List<Message> details;

    /** @see #getTimestamp() */
    protected Long timestamp;

    /** Saves whether the message was already {@link #i18n(SlingHttpServletRequest)}-ized. */
    protected transient boolean i18lized;

    /** @deprecated only for JSON deserialization. */
    @Deprecated
    public Message() {
        // empty
    }

    /**
     * Creates a message.
     *
     * @param level     the level of the message, default {@link Level#info}
     * @param message   the message, possibly with placeholders {@quote {}} for arguments
     * @param arguments optional arguments placed in placeholders. Caution: must be primitive types if this is to be
     *                  transmitted with JSON!
     */
    public Message(@Nullable Level level, @Nonnull String message, Object... arguments) {
        this.message = message;
        this.level = level;
        this.category = category;
        this.arguments = arguments != null && arguments.length > 0 ? arguments : null;
        timestamp = System.currentTimeMillis();
    }

    /**
     * Convenience-method - constructs with {@link Level#error}.
     *
     * @param message   the message, possibly with placeholders {@quote {}} for arguments
     * @param arguments optional arguments placed in placeholders. Caution: must be primitive types if this is to be
     *                  transmitted with JSON!
     */
    @Nonnull
    public static Message error(@Nonnull String message, Object... arguments) {
        return new Message(Level.error, message, arguments);
    }

    /**
     * Convenience-method - constructs with {@link Level#warn}.
     *
     * @param message   the message, possibly with placeholders {@quote {}} for arguments
     * @param arguments optional arguments placed in placeholders. Caution: must be primitive types if this is to be
     *                  transmitted with JSON!
     */
    @Nonnull
    public static Message warn(@Nonnull String message, Object... arguments) {
        return new Message(Level.warn, message, arguments);
    }

    /**
     * Convenience-method - constructs with {@link Level#info}.
     *
     * @param message   the message, possibly with placeholders {@quote {}} for arguments
     * @param arguments optional arguments placed in placeholders. Caution: must be primitive types if this is to be
     *                  transmitted with JSON!
     */
    @Nonnull
    public static Message info(@Nonnull String message, Object... arguments) {
        return new Message(Level.info, message, arguments);
    }

    /**
     * Convenience-method - constructs with {@link Level#debug}.
     *
     * @param message   the message, possibly with placeholders {@quote {}} for arguments
     * @param arguments optional arguments placed in placeholders. Caution: must be primitive types if this is to be
     *                  transmitted with JSON!
     */
    @Nonnull
    public static Message debug(@Nonnull String message, Object... arguments) {
        return new Message(Level.debug, message, arguments);
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

    /** The kind of message - informational, warning, error. Default is {@link Level#info}. */
    @Nonnull
    public Level getLevel() {
        return level != null ? level : Level.info;
    }

    /**
     * The human readable message text, possibly with argument placeholders {@literal {}}. If i18n is wanted, this
     * is the key for the i18n - all variable parts should be put into the arguments. Mandatory part of a message.
     */
    @Nonnull
    public String getMessage() {
        return message;
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
     * Can optionally be used to categorize messages for filtering / sorting. This is not meant to be shown directly
     * to the user.
     */
    @Nullable
    public String getCategory() {
        return category;
    }

    /**
     * Sets the optional category: can optionally be used to categorize messages for filtering / sorting. This is not
     * meant to be shown directly to the user.
     *
     * @return this for builder style operation chaining.
     */
    @Nonnull
    public Message setCategory(@Nullable String category) {
        this.category = category;
        return this;
    }

    /**
     * Optional: label of the field which the message is about, primarily in validation messages. (Not the
     * human-readable but the programmatical id is meant.)
     */
    @Nullable
    public String getLabel() {
        return label;
    }

    /**
     * Sets the optional context: a label of the field which the message is about, primarily in validation messages.
     * (Not the human-readable but the programmatical id is meant.)
     *
     * @return this for builder style operation chaining.
     */
    @Nonnull
    public Message setLabel(@Nullable String label) {
        this.label = label;
        return this;
    }

    /**
     * Optional: a context of the message, such as the dialog tab in a validation message. (Not the
     * human-readable but the programmatical id is meant.)
     */
    @Nullable
    public String getContext() {
        return context;
    }

    /**
     * Sets a context: a context of the message, such as the dialog tab in a validation message. (Not the
     * human-readable but the programmatical id is meant.)
     *
     * @return this for builder style operation chaining.
     */
    @Nonnull
    public Message setContext(@Nullable String context) {
        this.context = context;
        return this;
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
     * Internationalizes the message according to the requests locale. This modifies the message: the
     * {@link #getMessage()} is looked up as i18n key, and then the arguments are placed into the placeholders and
     * then cleared. Recommended only after {@link #logInto(Logger)} or {@link #logInto(Logger, Throwable)}.
     *
     * @return this message for builder-style operation-chaining.
     */
    @Nonnull
    public Message i18n(SlingHttpServletRequest request) {
        if (!i18lized) {
            if (StringUtils.isNotBlank(message)) {
                String newMessage = I18N.get(request, message);
                if (arguments != null && arguments.length > 0) {
                    newMessage = LoggerFormat.format(newMessage, arguments);
                }
                if (StringUtils.isNotBlank(newMessage)) {
                    message = newMessage;
                    arguments = null;
                    i18lized = true;
                }
            }
        } else { // already i18lized - misuse
            LOG.warn("Second i18n on same message", new Exception("Stacktrace for second i18n, not thrown"));
        }
        return this;
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
        appendFormattedTo(buf, "", level);
        return buf.toString();
    }

    protected void appendFormattedTo(StringBuilder buf, String indent, Level baseLevel) {
        buf.append(indent);
        if (level != null && baseLevel != null && level != baseLevel) {
            buf.append(level.name()).append(": ");
        }
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
                detail.appendFormattedTo(buf, addIndent, baseLevel);
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

package com.composum.platform.commons.logging;

import org.apache.sling.api.SlingHttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A collection of {@link Message}s for humans - also meant for transmitting them via JSON. */
public class MessageContainer {

    private static final Logger LOG = LoggerFactory.getLogger(MessageContainer.class);

    /** A logger where {@link #add(Message)} automatically logs to. */
    @Nullable
    protected transient final Logger log;
    /** @see #getMessages() */
    @Nullable
    protected List<Message> messages;

    /**
     * Default constructor which means we do not log added messages - usually it's better to use
     * {@link #MessageContainer(Logger)} and log things immediately.
     */
    public MessageContainer() {
        log = null;
    }

    /**
     * Constructor that allows to set a logger to which {@link #add(Message)} automatically writes the messages.
     *
     * @param log an optional log to which added messages are logged.
     */
    public MessageContainer(@Nullable Logger log) {
        this.log = log;
    }

    /** A (unmodifiable) list of messages. */
    @Nonnull
    public List<Message> getMessages() {
        return messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
    }

    /**
     * Adds a message to the container, and logs it into the logger if one was specified for this container.
     * The intended usecase is with a logger, so we log a warning if it's called with a throwable but
     * we have no logger, so that Stacktraces don't disappear accidentially.
     *
     * @return this MessageContainer, for builder-style operation chaining.
     */
    @Nonnull
    public MessageContainer add(@Nullable Message message, @Nullable Throwable throwable) {
        if (message != null) {
            if (messages == null) { messages = new ArrayList<>(); }
            messages.add(message);
            if (log != null) {
                message.logInto(log, throwable);
            } else if (throwable != null) { // very likely a misuse
                LOG.warn("Received throwable but have no logger: {}", message.toFormattedMessage(), throwable);
            }

        }
        return this;
    }

    /**
     * Adds a message to the container, and logs it into the logger if one was specified for this container.
     *
     * @return this MessageContainer, for builder-style operation chaining.
     */
    @Nonnull
    public MessageContainer add(@Nullable Message message) {
        return add(message, null);
    }

    /**
     * Internationalizes the message according to the requests locale. This modifies the messages - see
     * {@link Message#i18n(SlingHttpServletRequest)}.
     *
     * @return this container for builder-style operation-chaining.
     * @see Message#i18n(SlingHttpServletRequest)
     */
    @Nonnull
    public MessageContainer i18n(@Nonnull SlingHttpServletRequest request) {
        for (Message message : getMessages()) {
            message.i18n(request);
        }
        return this;
    }

}

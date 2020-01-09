package com.composum.platform.commons.logging;

import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A collection of {@link Message}s for humans - also meant for transmitting them via JSON. */
public class MessageContainer {

    /** A logger where {@link #addMessage(Message)} automatically logs to. */
    protected transient final Logger log;
    /** @see #getMessages() */
    protected List<Message> messages;

    /** Default constructor. */
    public MessageContainer() {
        log = null;
    }

    /**
     * Constructor that allows to set a logger to which {@link #addMessage(Message)} automatically writes the messages.
     *
     * @param log an optional log to which added messages are logged.
     */
    public MessageContainer(Logger log) {
        this.log = log;
    }

    /** A (unmodifiable) list of messages. */
    @Nonnull
    public List<Message> getMessages() {
        return messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
    }

    /**
     * Adds a message to the container, and logs it into the logger if one was specified for this container.
     *
     * @return this MessageContainer, for builder-style operation chaining.
     */
    @Nonnull
    public MessageContainer addMessage(@Nullable Message message) {
        if (message != null) {
            if (messages == null) { messages = new ArrayList<>(); }
            messages.add(message);
            if (log != null) {
                message.logInto(log);
            }
        }
        return this;
    }

}

package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.logging.Message;
import com.composum.sling.core.logging.MessageContainer;
import com.composum.sling.core.servlet.Status;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

/**
 * General exception reporting details about a failure of the replication process.
 */
public class ReplicationException extends Exception {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationException.class);

    protected ReplicationException.RetryAdvice retryadvice = RetryAdvice.NO_AUTOMATIC_RETRY;

    @Nonnull
    protected MessageContainer messages = new MessageContainer(LOG);

    public ReplicationException(@Nonnull Message message, @Nullable Exception e) {
        super(message.toFormattedMessage(), e);
        messages.add(message, e);
        if (e != null) {
            message.addDetail(Message.debug("Exception details: {}", e.toString()));
        }
    }

    /**
     * Indicates whether this is a temporary failure that is likely to go away when tried again (automatic retry recommended),
     * or something that needs to be presented to the user.
     */
    @Nullable
    public RetryAdvice getRetryadvice() {
        return retryadvice;
    }

    /**
     * A collection of messages describing the failure.
     */
    @Nonnull
    public MessageContainer getMessages() {
        return messages;
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getName())
                .append("{")
                .append(Integer.toHexString(super.hashCode()))
                .append("}")
                .append("retryadvice=").append(retryadvice);
        extendToString(sb);
        sb.append('}');
        extendToStringTail(sb);
        return sb.toString();
    }

    /**
     * Stub to add further attributes to {@link #toString()}.
     */
    protected void extendToString(StringBuilder sb) {
        // empty
    }

    /**
     * Stub to add further attributes to {@link #toString()} after the }.
     */
    protected void extendToStringTail(StringBuilder sb) {
        if (!messages.isEmpty()) {
            sb.append(" messages {{");
            for (Message message : messages.getMessages()) {
                sb.append(" ").append(message.toFormattedMessage());
            }
            sb.append("}}");
        }
    }

    /**
     * Sets this exception to {@link RetryAdvice#RETRY_IMMEDIATELY}.
     *
     * @return this (for builder style usage)
     */
    public ReplicationException asRetryable() {
        retryadvice = RetryAdvice.RETRY_IMMEDIATELY;
        return this;
    }

    /**
     * Writes the data from the exception into the {@link Status} object for a JSON response.
     */
    public void writeIntoStatus(Status status) {
        if (StringUtils.isBlank(status.getTitle())) {
            status.setTitle(getMessage());
            status.setStatus(SC_BAD_REQUEST);
        }
        status.data(ReplicationException.class.getName()).put(RetryAdvice.class.getName(), getRetryadvice());
        for (Message message : getMessages()) {
            status.addMessage(message);
        }
    }

    public enum RetryAdvice {
        /**
         * Temporary failure (e.g. because of concurrent modification) - can be retried immediately.
         */
        RETRY_IMMEDIATELY,
        /**
         * Permanent failure - manual intervention needed.
         */
        NO_AUTOMATIC_RETRY
    }

}

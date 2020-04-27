package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.logging.MessageContainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * General exception reporting details about a failure of the replication process.
 */
public class ReplicationException {

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

    protected ReplicationException.RetryAdvice retryadvice = RetryAdvice.NO_AUTOMATIC_RETRY;

    @Nonnull
    protected final MessageContainer messages = new MessageContainer();

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

    protected String originalToString() {
        return super.toString();
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getName())
                .append("{")
                .append(Integer.toHexString(super.hashCode()))
                .append("}")
                .append("retryadvice=").append(retryadvice)
                .append(", messages=").append(messages);
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
        // empty
    }

}

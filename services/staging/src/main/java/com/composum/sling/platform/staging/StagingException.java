package com.composum.sling.platform.staging;

public class StagingException extends RuntimeException {

    public StagingException() {
    }

    public StagingException(String message) {
        super(message);
    }

    public StagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public StagingException(Throwable cause) {
        super(cause);
    }

    public StagingException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

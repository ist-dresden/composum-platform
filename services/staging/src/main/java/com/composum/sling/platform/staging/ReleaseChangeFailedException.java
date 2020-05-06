package com.composum.sling.platform.staging;

/**
 * Informs that a release change failed, and a full site replication is needed. If there are several failures,
 * those are appended to {@link ReleaseChangeFailedException#getSuppressed()}.
 */
public class ReleaseChangeFailedException extends Exception {

    private final ReleaseChangeEventListener.ReleaseChangeEvent releaseChangeEvent;

    public ReleaseChangeFailedException(String message, ReleaseChangeEventListener.ReleaseChangeEvent releaseChangeEvent) {
        super(message);
        this.releaseChangeEvent = releaseChangeEvent;
    }

    public ReleaseChangeFailedException(String message, Exception e, ReleaseChangeEventListener.ReleaseChangeEvent releaseChangeEvent) {
        super(message, e);
        this.releaseChangeEvent = releaseChangeEvent;
    }

    public ReleaseChangeEventListener.ReleaseChangeEvent getReleaseChangeEvent() {
        return releaseChangeEvent;
    }
}

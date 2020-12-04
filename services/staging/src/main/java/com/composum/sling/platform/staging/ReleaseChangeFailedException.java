package com.composum.sling.platform.staging;

/**
 * Informs that a release change failed, and a full site replication is needed. If there are several failures,
 * those are appended to {@link ReleaseChangeFailedException#getSuppressed()}.
 */
public class ReleaseChangeFailedException extends Exception {

    private final ReleaseChangeEvent releaseChangeEvent;

    public ReleaseChangeFailedException(String message, ReleaseChangeEvent releaseChangeEvent) {
        super(message);
        this.releaseChangeEvent = releaseChangeEvent;
    }

    public ReleaseChangeFailedException(String message, Exception e, ReleaseChangeEvent releaseChangeEvent) {
        super(message, e);
        this.releaseChangeEvent = releaseChangeEvent;
    }

    public ReleaseChangeEvent getReleaseChangeEvent() {
        return releaseChangeEvent;
    }
}

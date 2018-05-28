package com.composum.sling.platform.security;

import javax.servlet.http.HttpServletRequest;

public enum AccessMode {

    AUTHOR, PREVIEW, PUBLIC;

    public static AccessMode accessModeValue(Object value, AccessMode defaultValue) {
        AccessMode mode = null;
        if (value != null) {
            try {
                mode = Enum.valueOf(AccessMode.class, value.toString().trim().toUpperCase());
            } catch (IllegalArgumentException iaex) {
                // ok, null...
            }
        }
        return mode != null ? mode : defaultValue;
    }

    public static AccessMode accessModeValue(Object value) {
        return accessModeValue(value, null);
    }

    public static AccessMode requestMode(HttpServletRequest request) {
        return accessModeValue(request.getAttribute(PlatformAccessFilter.ACCESS_MODE_KEY));
    }

    // access mode string values
    public static final String ACCESS_MODE_AUTHOR = AccessMode.AUTHOR.name();
    public static final String ACCESS_MODE_PREVIEW = AccessMode.PREVIEW.name();
    public static final String ACCESS_MODE_PUBLIC = AccessMode.PUBLIC.name();
}

/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.security;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PlatformAccessService {

    interface AccessContext {

        @NotNull
        SlingHttpServletRequest getRequest();

        @NotNull
        SlingHttpServletResponse getResponse();

        @NotNull
        ResourceResolver getResolver();
    }

    @Nullable
    AccessContext getAccessContext();
}

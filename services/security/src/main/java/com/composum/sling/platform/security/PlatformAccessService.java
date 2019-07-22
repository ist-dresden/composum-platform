/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.security;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PlatformAccessService {

    interface AccessContext {

        @Nonnull
        SlingHttpServletRequest getRequest();

        @Nonnull
        SlingHttpServletResponse getResponse();

        @Nonnull
        ResourceResolver getResolver();
    }

    @Nullable
    AccessContext getAccessContext();
}

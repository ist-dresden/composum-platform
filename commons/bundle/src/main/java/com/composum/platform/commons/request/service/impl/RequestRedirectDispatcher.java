package com.composum.platform.commons.request.service.impl;

import com.composum.platform.commons.request.service.RequestRedirectProvider;
import com.composum.platform.commons.request.service.RequestRedirectService;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Redirect Dispatcher"
        },
        immediate = true
)
public class RequestRedirectDispatcher implements RequestRedirectService {

    private static final Logger LOG = LoggerFactory.getLogger(RequestRedirectDispatcher.class);

    protected Map<String, RequestRedirectProvider> providers = Collections.synchronizedMap(new LinkedHashMap<>());

    @Reference(service = RequestRedirectProvider.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE)
    protected void bindRequestRedirectProvider(@NotNull final RequestRedirectProvider service) {
        LOG.info("bindRequestRedirectProvider({}:{})", service.getName(), service);
        RequestRedirectProvider unbind = providers.put(service.getName(), service);
        if (unbind != null) {
            LOG.warn("bindRequestRedirectProvider({}:{}) has replaced '{}'", service.getName(), service, unbind);
        }
    }

    protected void unbindRequestRedirectProvider(@NotNull final RequestRedirectProvider service) {
        LOG.info("unbindRequestRedirectProvider({}:{})", service.getName(), service);
        providers.remove(service.getName());
    }

    @Override
    public boolean redirectRequest(@NotNull final SlingHttpServletRequest request,
                                   @NotNull final SlingHttpServletResponse response) {
        for (RequestRedirectProvider provider : providers.values()) {
            if (provider.canHandle(request) && provider.redirectRequest(request, response)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("redirect handled by '{}' ({})", provider.getName(), request.getRequestURL());
                }
                return true;
            }
        }
        return false;
    }
}

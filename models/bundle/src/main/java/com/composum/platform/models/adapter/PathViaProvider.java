package com.composum.platform.models.adapter;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.ViaProviderType;
import org.apache.sling.models.spi.ViaProvider;
import org.slf4j.Logger;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The type Path via provider.
 *
 * @author Hans-Peter Stoerr
 */
@Component
@Service
public class PathViaProvider implements ViaProvider {

    private static final Logger LOG = getLogger(PathViaProvider.class);

    @Override
    public Class<? extends ViaProviderType> getType() {
        return PathViaProviderType.class;
    }

    @Override
    public Object getAdaptable(Object original, String value) {
        if (isBlank(value)) {
            return ORIGINAL;
        }
        Resource resource = getResource(original);
        Object result = null;
        if (null != resource) {
            result = resource.getChild(value);
        }
        return result;
    }

    protected Resource getResource(Object original) {
        Resource resource = null;
        if (original instanceof Resource) resource = (Resource) original;
        else if (original instanceof SlingHttpServletRequest) resource = ((SlingHttpServletRequest) original)
                .getResource();
        else if (original instanceof Adaptable) resource = ((Adaptable) original).adaptTo(Resource.class);
        return resource;
    }

}

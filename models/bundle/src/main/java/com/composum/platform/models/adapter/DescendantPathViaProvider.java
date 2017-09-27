package com.composum.platform.models.adapter;

import com.composum.platform.models.annotations.DescendantPath;
import com.composum.sling.core.BeanContext;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.apache.sling.models.annotations.Via;
import org.apache.sling.models.annotations.ViaProviderType;
import org.apache.sling.models.spi.ViaProvider;
import org.slf4j.Logger;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Selects a descendant resource for a {@link com.composum.sling.core.BeanContext}, {@link SlingHttpServletRequest} (or
 * {@link Resource}) as a @{@link Via} while keeping the type and other values except the resource. Use with {@link Via}
 * annotation: e.g. <code>@Self @Via (value = "config", type = DescendantPath.class) OtherSlingModelsClass
 * createdfromsubpathconfig</code>. The difference from using
 * {@link org.apache.sling.models.annotations.via.ChildResource} is that changes the resource but keeps the type of
 * the object being adapted from even if it is not a Resource, and thus supports e.g. the i18n functionality of
 * {@link com.composum.platform.models.annotations.Property}.
 *
 * @author Hans-Peter Stoerr
 */
@Component
@Service
public class DescendantPathViaProvider implements ViaProvider {

    private static final Logger LOG = getLogger(DescendantPathViaProvider.class);

    @Override
    public Class<? extends ViaProviderType> getType() {
        return DescendantPath.class;
    }

    @Override
    public Object getAdaptable(Object original, final String value) {
        if (isBlank(value)) {
            return ORIGINAL;
        }
        Object result = null;
        if (original instanceof Resource) result = ((Resource) original).getChild(value);
        else if (original instanceof SlingHttpServletRequest) {
            final SlingHttpServletRequest request = (SlingHttpServletRequest) original;
            if (null == request.getResource()) result = ORIGINAL;
            else result = new SlingHttpServletRequestWrapper(request) {
                @Override
                public Resource getResource() {
                    return request.getResource().getChild(value);
                }
            };
        } else if (original instanceof BeanContext) {
            BeanContext beanContext = (BeanContext) original;
            if (null != beanContext.getResource()) {
                result = beanContext.withResource(beanContext.getResource().getChild(value));
            } else result = ORIGINAL; // no change when no resource, anyway.
        } else if (original instanceof Adaptable) {
            Resource resource = ((Adaptable) original).adaptTo(Resource.class);
            result = null != resource ? resource.getChild(value) : null;
        }

        return result;
    }

}

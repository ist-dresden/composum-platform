package com.composum.platform.models.annotations;

import com.composum.platform.models.adapter.DescendantPathViaProvider;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.ViaProviderType;

/**
 * Selects a descendant resource for a {@link com.composum.sling.core.BeanContext}, {@link
 * org.apache.sling.api.SlingHttpServletRequest} (or {@link Resource}) as a @{@link
 * org.apache.sling.models.annotations.Via} while keeping the type and other values except the resource. Use with {@link
 * org.apache.sling.models.annotations.Via} annotation: e.g. <code>@Self @Via (value = "config", type =
 * DescendantPath.class) OtherSlingModelsClass createdfromsubpathconfig</code>. The difference from using e.g. {@link
 * org.apache.sling.models.annotations.via.ChildResource} is that changes the resource but keeps the type of the object
 * being adapted from even if it is not a Resource, and thus supports e.g. the i18n functionality of {@link
 * com.composum.platform.models.annotations.Property}. Marker class for the {@link DescendantPathViaProvider}.
 *
 * @author Hans-Peter Stoerr
 */
public class DescendantPath implements ViaProviderType {
    // deliberately empty
}

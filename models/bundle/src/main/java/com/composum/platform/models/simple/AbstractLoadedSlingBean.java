package com.composum.platform.models.simple;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.SlingBean;
import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;

/**
 * A lightweight {@link SlingBean} that is fully initialized from a resource and discards
 * the initialization resource, so that it's life span can exceed the life span of the
 * resources' resolver.
 */
public class AbstractLoadedSlingBean implements SlingBean {

    protected String name;
    protected String path;
    protected String type;
    protected String title;
    protected String description;

    /**
     * Uses the contexts 'resource' attribute for initialization with {@link #initialize(BeanContext, Resource)}.
     *
     * @param context the scripting context (e.g. a JSP PageContext or a Groovy scripting context)
     */
    @Override
    public void initialize(BeanContext context) {
        initialize(context, context.getResource());
    }

    /**
     * Initializes the model. Do not keep the context or the resource, as this is meant to exceed their lifetime!
     */
    @Override
    public void initialize(BeanContext beanContext, @Nonnull Resource resource) {
        this.name = resource.getName();
        this.path = resource.getPath();
        this.type = resource.getResourceType();
        @NotNull ValueMap vm = resource.getValueMap();
        this.title = vm.get("title", String.class);
        this.title = StringUtils.defaultIfBlank(title, vm.get(ResourceUtil.PROP_TITLE, String.class));
        this.description = vm.get("description", String.class);
        this.title = StringUtils.defaultIfBlank(description, vm.get(ResourceUtil.PROP_DESCRIPTION, String.class));
    }

    /**
     * initialize bean using the context an the resource given explicitly
     */
    public AbstractLoadedSlingBean(BeanContext context, Resource resource) {
        initialize(context, resource);
    }

    /**
     * initialize bean using the context with the 'resource' attribute within
     */
    public AbstractLoadedSlingBean(BeanContext context) {
        initialize(context);
    }

    /**
     * if this constructor is used, the bean must be initialized using the 'initialize' method!
     */
    public AbstractLoadedSlingBean() {
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getType() {
        return type;
    }

    /**
     * Property "title" or "jcr:title".
     */
    public String getTitle() {
        return title;
    }

    /**
     * Property "description" or "jcr:description".
     */
    public String getDescription() {
        return description;
    }
}

package com.composum.platform.commons.proxy;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * the general caching service configuration 'object'
 */
@ObjectClassDefinition(
        name = "Composum HTTP Proxy Service Configuration"
)
public @interface ProxyConfiguration {

    @AttributeDefinition(
            description = "the general on/off switch for this service"
    )
    boolean enabled() default true;

    @AttributeDefinition(
            description = "the name (short descriptive key) of the service"
    )
    String name();

    @AttributeDefinition(
            description = "the pattern of the target URL (proxy suffix) handled by the proxy service"
    )
    String targetPattern();

    @AttributeDefinition(
            description = "the URL for the proxy request (optional; if different from the target pattern)"
    )
    String targetUrl();

    @AttributeDefinition(
            description = "a comma separated list of tags to strip from the result (this keeps the tags body)"
    )
    String[] tags_to_strip() default {"html", "body"};

    @AttributeDefinition(
            description = "a comma separated list of tags to drop from the result (this removes the tags body)"
    )
    String[] tags_to_drop() default {"head", "style", "script"};

    @AttributeDefinition(
            description = "the repository path which repesents this service (for ACL based permission check)"
    )
    String referencePath();

    @AttributeDefinition()
    String webconsole_configurationFactory_nameHint() default
            "{name} (enabled: {enabled}, target: {targetPattern}, ref: {referencePath})";
}

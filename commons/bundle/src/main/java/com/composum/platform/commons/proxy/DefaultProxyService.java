package com.composum.platform.commons.proxy;

import com.composum.platform.commons.credentials.CredentialService;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Component(
        service = ProxyService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Default Proxy Service"
        },
        immediate = true
)
@Designate(ocd = DefaultProxyService.Configuration.class, factory = true)
public class DefaultProxyService implements ProxyService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultProxyService.class);

    @Reference
    protected CredentialService credentialService;

    @Nullable
    protected volatile Configuration config;

    protected String proxyKey;

    @Activate
    @Modified
    protected void activate(Configuration configuration) {
        proxyKey = configuration.proxyKey();
        this.config = configuration;
    }

    @Deactivate
    protected void deactivate() {
        this.config = null;
    }

    @Override
    public boolean isEnabled() {
        Configuration cfg = config;
        return cfg != null && cfg.enabled();
    }

    @Nonnull
    @Override
    public String getProxyKey() {
        return proxyKey;
    }

    @Nonnull
    @Override
    public String getTitle() {
        Configuration cfg = config;
        return cfg != null ? cfg.title() : null;
    }

    @Nullable
    @Override
    public String getDescription() {
        Configuration cfg = config;
        return cfg != null ? cfg.description() : null;
    }

    @Override
    public void initHttpContext(@Nonnull HttpClientContext context, @Nullable ResourceResolver resolver) throws RepositoryException {
        Configuration cfg = config;
        if (cfg == null || !isEnabled()) { return; }

        if (isNotBlank(cfg.proxyHost())) {
            RequestConfig.Builder requestConfigBuilder = context.getRequestConfig() != null ?
                    RequestConfig.copy(context.getRequestConfig()) : RequestConfig.custom();
            HttpHost proxy = new HttpHost(cfg.proxyHost(), cfg.proxyPort());
            requestConfigBuilder.setProxy(proxy);
            context.setRequestConfig(requestConfigBuilder.build());
        }

        if (StringUtils.isNotBlank(cfg.proxyCredentialId())) {
            credentialService.initHttpContextCredentials(context, new AuthScope(cfg.proxyHost(), cfg.proxyPort()),
                    cfg.proxyCredentialId(), resolver);
        }
    }

    @ObjectClassDefinition(name = "Composum Platform Default Proxy Service",
            description = "Configures a HTTP(S) Proxy."
    )
    @interface Configuration {
        @AttributeDefinition(name = "enabled", required = true, description =
                "The on/off switch for the proxy service")
        boolean enabled() default false;

        @AttributeDefinition(name = "Proxy key", required = true, description =
                "The unique key for the proxy.")
        String proxyKey();

        @AttributeDefinition(name = "Title", required = true, description =
                "A short human-readable name for the proxy.")
        String title();

        @AttributeDefinition(name = "Description", required = false, description =
                "A human-readable description for the proxy (e.g. intended use, ...).")
        String description();

        @AttributeDefinition(name = "Proxy Host", required = true, description =
                "The host for the Proxy.")
        String proxyHost();

        @AttributeDefinition(name = "Proxy Port", required = true, description =
                "The port for the Proxy.")
        int proxyPort();

        @AttributeDefinition(name = "Proxy Credential ID", required = false, description =
                "Optional, an ID for the credentialsserviced to access the proxy's credentials.")
        String proxyCredentialId();
    }
}

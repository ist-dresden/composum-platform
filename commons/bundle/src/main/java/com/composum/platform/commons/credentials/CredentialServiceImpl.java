package com.composum.platform.commons.credentials;

import com.composum.platform.commons.crypt.CryptoService;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Component(
        service = CredentialService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Credential Service"
        },
        immediate = true
)
public class CredentialServiceImpl implements CredentialService {
    private static final Logger LOG = LoggerFactory.getLogger(CredentialServiceImpl.class);

    @Reference
    protected CryptoService cryptoService;

    @Override
    public void initHttpContextCredentials(@Nonnull HttpClientContext context, @Nonnull AuthScope authScope, @Nonnull String proxyCredentialId, @Nullable ResourceResolver resolver) {
        // FIXME(hps,21.02.20) replace this stub implementation that always adds admin/admin
        CredentialsProvider credsProvider = context.getCredentialsProvider() != null ?
                context.getCredentialsProvider() : new BasicCredentialsProvider();

        credsProvider.setCredentials(authScope, new UsernamePasswordCredentials("admin", "admin"));

        context.setCredentialsProvider(credsProvider);
    }
}

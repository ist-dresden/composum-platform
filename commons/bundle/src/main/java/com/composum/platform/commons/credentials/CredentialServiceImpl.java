package com.composum.platform.commons.credentials;

import com.composum.platform.commons.crypt.CryptoService;
import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
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

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Component(
        service = CredentialService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Credential Service"
        }
)
@Designate(ocd = CredentialServiceImpl.Configuration.class)
public class CredentialServiceImpl implements CredentialService {
    private static final Logger LOG = LoggerFactory.getLogger(CredentialServiceImpl.class);

    /** The path in the JCR where the credentials are stored. */
    protected static final String PATH_CONFIGS = "/conf/composum/platform/security/credentials";

    /** A path the user has to have read-access to in order to use the credentials (for ACL based permission check). */
    public static final String PROP_REFERENCEPATH = "referencePath";
    /** Property that tells whether the configuration is enabled.. */
    public static final String PROP_ENABLED = "enabled";
    /** The encoded password. */
    public static final String PROP_ENCODED_PASSWD = "encodedPasswd";
    /** The encoded username. */
    public static final String PROP_ENCODED_USER = "encodedUser";
    /** Alternatively, the unencoded username (for testing purposes only). */
    public static final String PROP_USER = "user";
    /** Alternatively, the unencoded password (for testing purposes only). */
    public static final String PROP_PASSWD = "passwd";

    @Reference
    protected CryptoService cryptoService;

    @Reference
    protected ResourceResolverFactory resolverFactory;

    protected volatile Configuration config;

    @Override
    public void initHttpContextCredentials(@Nonnull HttpClientContext context, @Nonnull AuthScope authScope,
                                           @Nonnull String credentialId, @Nullable ResourceResolver resolver) throws RepositoryException {
        CredentialConfiguration credentials = getCredentials(credentialId);
        if (credentials == null) { throw new IllegalArgumentException("Wrong credential ID " + credentialId); }
        if (!credentials.enabled) { throw new IllegalArgumentException("Credentials are not enabled: " + credentialId);}
        if (StringUtils.isNotBlank(credentials.referencePath)) {
            Resource aclResource = resolver != null ? resolver.getResource(credentials.referencePath) : null;
            if (aclResource == null) {
                throw new RepositoryException("No rights to acl path for " + credentialId);
            }
        }

        CredentialsProvider credsProvider = context.getCredentialsProvider() != null ?
                context.getCredentialsProvider() : new BasicCredentialsProvider();
        credsProvider.setCredentials(authScope, new UsernamePasswordCredentials(credentials.user, credentials.passwd));
        context.setCredentialsProvider(credsProvider);
    }


    /** Internal method to retrieve credential data. */
    protected CredentialConfiguration getCredentials(String proxyCredentialId) {
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(null)) {
            String path = SlingResourceUtil.appendPaths(PATH_CONFIGS, proxyCredentialId);
            if (!SlingResourceUtil.isSameOrDescendant(PATH_CONFIGS, path)) {
                throw new IllegalArgumentException("No . or .. allowed in credential ID " + proxyCredentialId);
            }
            Resource resource = resolver.getResource(path);
            if (resource == null) {
                throw new IllegalArgumentException("No credentials found with key " + proxyCredentialId);
            }
            return new CredentialConfiguration(resource);
        } catch (LoginException e) { // should be impossible.
            throw new IllegalStateException("Can't get service resolver.", e);
        }
    }

    @Override
    public String encodePassword(@Nonnull String password) {
        checkEnabled();
        return cryptoService.encrypt(password, config.masterPassword());
    }

    protected void checkEnabled() {
        if (!isEnabled()) { throw new IllegalStateException("Service not enabled."); }
    }

    @Override
    public boolean isEnabled() {
        Configuration cfg = config;
        return cfg != null && cfg.enabled();
    }

    @Activate
    @Modified
    protected void activate(CredentialServiceImpl.Configuration configuration) {
        this.config = configuration;
    }

    @Deactivate
    protected void deactivate() {
        this.config = null;
    }

    @ObjectClassDefinition(name = "Composum Platform Credential Service",
            description = "A place to put credentials for use with other services."
    )
    protected @interface Configuration {
        @AttributeDefinition(name = "enabled", required = true, description = "The on/off switch")
        boolean enabled() default false;

        // TODO(hps,21.02.20) think of at least an obfuscated way to store this.
        @AttributeDefinition(name = "Master Password", required = true,
                description = "The password to decrypt the credentials.")
        String masterPassword();
    }

    /** Captures the data from a resource. */
    protected class CredentialConfiguration {
        public final String referencePath;
        public final String user;
        public final String passwd;
        public final boolean enabled;

        protected CredentialConfiguration(Resource resource) {
            ValueMap vm = resource.getValueMap();
            this.referencePath = vm.get(PROP_REFERENCEPATH, String.class);
            enabled = vm.get(PROP_ENABLED, true);
            String encodedUser = vm.get(PROP_ENCODED_USER, String.class);
            String encodedPasswd = vm.get(PROP_ENCODED_PASSWD, String.class);
            String user = vm.get(PROP_USER, String.class);
            String passwd = vm.get(PROP_PASSWD, String.class);
            if (isBlank(user) && isNotBlank(encodedUser)) {
                user = cryptoService.decrypt(encodedUser, config.masterPassword());
            }
            if (isBlank(passwd) && isNotBlank(encodedPasswd)) {
                passwd = cryptoService.decrypt(encodedPasswd, config.masterPassword());
            }
            this.user = user;
            this.passwd = passwd;
        }
    }

}

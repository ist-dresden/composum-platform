package com.composum.platform.commons.credentials.impl;

import com.composum.platform.commons.credentials.CredentialService;
import com.composum.platform.commons.crypt.CryptoService;
import com.composum.platform.commons.util.TokenUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.sling.api.resource.*;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.*;

@Component(
        service = CredentialService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Credential Service"
        }
)
@Designate(ocd = CredentialServiceImpl.Configuration.class)
public class CredentialServiceImpl implements CredentialService {
    private static final Logger LOG = LoggerFactory.getLogger(CredentialServiceImpl.class);

    /**
     * The path in the JCR where the credentials are stored.
     */
    protected static final String PATH_CONFIGS = "/var/composum/platform/security/credentials";

    /**
     * A path the user has to have read-access to in order to use the credentials (for ACL based permission check).
     */
    public static final String PROP_REFERENCEPATH = "referencePath";
    /**
     * Property that tells whether the configuration is enabled..
     */
    public static final String PROP_ENABLED = "enabled";
    /**
     * The encrypted password.
     */
    public static final String PROP_ENCRYPTED_PASSWD = "encryptedPasswd";
    /**
     * The encrypted username.
     */
    public static final String PROP_ENCRYPTED_USER = "encryptedUser";
    /**
     * Alternatively, the unencrypted username (for testing purposes only).
     */
    public static final String PROP_USER = "user";
    /**
     * Alternatively, the unencrypted password (for testing purposes only).
     */
    public static final String PROP_PASSWD = "passwd";
    /**
     * Optional, restricts the use of the credentials to certain types of credentials, such as {@link #TYPE_EMAIL} or {@link #TYPE_HTTP}. Can have multiple values.
     */
    public static final String PROP_TYPE = "credentialType";

    /**
     * Prefix marking a token returned by {@link #getAccessToken(String, ResourceResolver, String)}.
     */
    public static final String PREFIX_TOKEN = "credentialtoken:";

    @Reference
    protected CryptoService cryptoService;

    @Reference
    protected ResourceResolverFactory resolverFactory;

    protected volatile Configuration config;
    protected volatile String masterPassword;

    @Override
    public void initHttpContextCredentials(@Nonnull HttpClientContext context, @Nonnull AuthScope authScope,
                                           @Nonnull String credentialIdOrToken, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException {
        CredentialConfiguration credentials = getCredentialConfiguration(credentialIdOrToken, aclCheckResolver);
        verifyTypeAllowed(credentials, TYPE_HTTP);

        CredentialsProvider credsProvider = context.getCredentialsProvider() != null ?
                context.getCredentialsProvider() : new BasicCredentialsProvider();
        credsProvider.setCredentials(authScope, new UsernamePasswordCredentials(credentials.user, credentials.passwd));
        context.setCredentialsProvider(credsProvider);
    }

    @Override
    public Authenticator getMailAuthenticator(@Nonnull String credentialIdOrToken, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException {
        CredentialConfiguration credentials = getCredentialConfiguration(credentialIdOrToken, aclCheckResolver);
        verifyTypeAllowed(credentials, TYPE_EMAIL);
        PasswordAuthentication passwordAuthentication = new PasswordAuthentication(credentials.user, credentials.passwd);
        Authenticator result = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return passwordAuthentication;
            }
        };
        return result;
    }

    /**
     * The access token is an encrypted and tamper-proofed (well, more or less signed) string containing the credentialId, the type, the
     * time it was created and until which it is valid.
     */
    @Override
    public String getAccessToken(@Nonnull String credentialId, @Nullable ResourceResolver aclCheckResolver, @Nonnull String type)
            throws RepositoryException, IllegalArgumentException, IllegalStateException {
        CredentialConfiguration credentials = getCredentialConfiguration(credentialId, aclCheckResolver);
        verifyTypeAllowed(credentials, type);
        LOG.debug("Creating token for {}", credentialId);
        String token = TokenUtil.join(credentials.id, type, System.currentTimeMillis(),
                System.currentTimeMillis() + MILLISECONDS.convert(config.tokenValiditySeconds(), SECONDS));
        token = TokenUtil.addHash(token);
        token = PREFIX_TOKEN + cryptoService.encrypt(token, getMasterPassword());
        return token;
    }

    protected void verifyTypeAllowed(CredentialConfiguration credentials, String type) {
        if (credentials.types != null && !credentials.types.isEmpty()) {
            if (!credentials.types.contains(type)) {
                LOG.info("Trying to use credentials {} having type {} with type {}",
                        credentials.id, credentials.types, type);
                throw new IllegalArgumentException("Credential type not permitted: " + type);
            }
        }
    }

    /**
     * Reads the credentials from a resource after checking that the service and credentials are enabled, and the
     * acl path is readable by aclCheckResolver.
     */
    @Nonnull
    protected CredentialConfiguration getCredentialConfiguration(@Nonnull String tokenOrCredentialId, @Nullable ResourceResolver aclCheckResolver)
            throws RepositoryException {
        if (!isEnabled()) {
            throw new IllegalStateException("CredentialService is not enabled.");
        }
        boolean isToken = startsWith(PREFIX_TOKEN, tokenOrCredentialId);
        CredentialConfiguration credentials = readCredentials(tokenOrCredentialId);
        if (credentials == null) {
            throw new IllegalArgumentException("Wrong credential ID " + tokenOrCredentialId);
        }
        if (!credentials.enabled) {
            throw new IllegalArgumentException("Credentials are not enabled: " + tokenOrCredentialId);
        }
        if (!isToken && isNotBlank(credentials.referencePath)) {
            Resource aclResource = aclCheckResolver != null ? aclCheckResolver.getResource(credentials.referencePath) : null;
            if (aclResource == null) {
                throw new RepositoryException("No rights to acl path for " + tokenOrCredentialId);
            }
        }
        return credentials;
    }

    /**
     * Internal method to retrieve credential data.
     */
    protected CredentialConfiguration readCredentials(String tokenOrCredentialId) throws PathNotFoundException {
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(null)) {
            String credentialId;
            String tokenRequiredType = null;
            if (StringUtils.startsWith(tokenOrCredentialId, PREFIX_TOKEN)) {
                String token = decodeAndCheckToken(tokenOrCredentialId);
                List<String> decoded = TokenUtil.extract(token, 4);
                // was constructed with TokenUtil.join(credentials.id, type, System.currentTimeMillis(),
                //      System.currentTimeMillis() + MILLISECONDS.convert(config.tokenValiditySeconds(), SECONDS));
                credentialId = decoded.get(0);
                long now = System.currentTimeMillis();
                long creationTime = Long.parseLong(decoded.get(2));
                long validUntilTime = Long.parseLong(decoded.get(3));
                if (now < creationTime || now > validUntilTime
                        || creationTime < now - MILLISECONDS.convert(config.tokenValiditySeconds(), SECONDS)) {
                    throw new IllegalArgumentException("Token time out of range.");
                }
                tokenRequiredType = decoded.get(1);
                LOG.info("Credential retrieved with token: {}", credentialId);
                // FIXME(hps,07.09.20) We also should check something about the current user.
            } else {
                credentialId = tokenOrCredentialId;
            }
            String path = SlingResourceUtil.appendPaths(PATH_CONFIGS, credentialId);
            if (!SlingResourceUtil.isSameOrDescendant(PATH_CONFIGS, path)) {
                throw new IllegalArgumentException("No . or .. allowed in credential ID " + credentialId);
            }
            Resource resource = resolver.getResource(path);
            if (resource == null) {
                throw new PathNotFoundException("No credentials found with key " + credentialId);
            }
            CredentialConfiguration credentialConfiguration = new CredentialConfiguration(credentialId, resource);
            if (StringUtils.isNotBlank(tokenRequiredType)) {
                verifyTypeAllowed(credentialConfiguration, tokenRequiredType);
                credentialConfiguration.types = Arrays.asList(tokenRequiredType);
            }
            return credentialConfiguration;
        } catch (LoginException e) { // should be impossible.
            throw new IllegalStateException("Can't get service resolver.", e);
        }
    }

    protected String decodeAndCheckToken(String encodedToken) {
        String token = cryptoService.decrypt(encodedToken.substring(PREFIX_TOKEN.length()), getMasterPassword());
        TokenUtil.checkHash(token);
        token = TokenUtil.removeHash(token);
        return token;
    }

    @Override
    public String encodePassword(@Nonnull String password) {
        checkEnabled();
        return cryptoService.encrypt(password, getMasterPassword());
    }

    @Nonnull
    protected String getMasterPassword() {
        if (isNotBlank(masterPassword)) {
            return masterPassword;
        }
        if (isNotBlank(config.masterPasswordFile())) {
            File passwdFile = new File(config.masterPasswordFile());
            if ((!passwdFile.exists() || passwdFile.length() == 0) && config.createPasswordFileIfMissing()) {
                writePasswordFile(passwdFile);
            }
            if (passwdFile.exists() && passwdFile.length() > 0) {
                try (FileInputStream fin = new FileInputStream(passwdFile)) {
                    masterPassword = IOUtils.toString(fin, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOG.error("Trouble reading password file " + passwdFile.getAbsolutePath(), e);
                    throw new IllegalStateException("Trouble reading password file " + passwdFile.getAbsolutePath(), e);
                }
            }
        }
        if (StringUtils.isBlank(masterPassword)) {
            masterPassword = config.masterPassword();
        }
        return masterPassword;
    }

    /**
     * Writes a random password to the given password file.
     */
    protected void writePasswordFile(File passwdFile) {
        SecureRandom rnd = new SecureRandom();
        String password = RandomStringUtils.random(1024, 32, 126, false, false, null, rnd);
        try (FileOutputStream fout = new FileOutputStream(passwdFile)) {
            fout.write(password.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.error("Problem writing password file " + passwdFile.getAbsolutePath(), e);
            throw new IllegalStateException(e);
        }
        LOG.info("Initialized master password with random key.");
    }

    protected void checkEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Service not enabled.");
        }
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
        this.masterPassword = null;
    }

    @Deactivate
    protected void deactivate() {
        this.config = null;
        this.masterPassword = null;
    }

    @ObjectClassDefinition(name = "Composum Platform Credential Service",
            description = "A place to put credentials for use with other services."
    )
    protected @interface Configuration {

        @AttributeDefinition(name = "enabled", required = true, description = "The on/off switch")
        boolean enabled() default false;

        @AttributeDefinition(name = "Master Password", required = false,
                description = "The password to decrypt the credentials. This is an alternative choice to a password " +
                        "file. Caution: the password is stored in plaintext in the OSGI config file.")
        String masterPassword();

        @AttributeDefinition(name = "Master Passwordfile path", required = false, type = AttributeType.STRING,
                description = "A relative or absolute path to a file containing the password to decrypt the " +
                        "credentials. This is an alternative " +
                        "choice to setting the password directly. Caution: the password is stored in plaintext in the " +
                        "file.")
        String masterPasswordFile();

        @AttributeDefinition(name = "Create Master Passwordfile", required = false,
                description = "If a path for a master password is set but it is empty or missing, the service tries to " +
                        "write a random password to this file.")
        boolean createPasswordFileIfMissing();

        @AttributeDefinition(name = "Token validity time", required = false,
                description = "Time in seconds an access token is valid.")
        int tokenValiditySeconds() default 86400 * 3;

    }

    /**
     * Captures the data from a resource.
     */
    protected class CredentialConfiguration {
        public final String id;
        public final String referencePath;
        public final String user;
        public final String passwd;
        public final boolean enabled;
        public Collection<String> types;

        protected CredentialConfiguration(String credentialId, Resource resource) {
            id = credentialId;
            ValueMap vm = resource.getValueMap();
            this.referencePath = vm.get(PROP_REFERENCEPATH, String.class);
            enabled = vm.get(PROP_ENABLED, true);
            String encryptedUser = vm.get(PROP_ENCRYPTED_USER, String.class);
            String encryptedPasswd = vm.get(PROP_ENCRYPTED_PASSWD, String.class);
            String user = vm.get(PROP_USER, String.class);
            String passwd = vm.get(PROP_PASSWD, String.class);
            if (isBlank(user) && isNotBlank(encryptedUser)) {
                user = cryptoService.decrypt(encryptedUser, getMasterPassword());
            }
            if (isBlank(passwd) && isNotBlank(encryptedPasswd)) {
                passwd = cryptoService.decrypt(encryptedPasswd, getMasterPassword());
            }
            this.user = user;
            this.passwd = passwd;
            String[] typenames = vm.get(PROP_TYPE, String[].class);
            types = typenames != null && typenames.length > 0 ? Arrays.asList(typenames) : null;
        }
    }

    //-------- ideas / scribble

    /**
     * a Vault is a set of credentials with its own 'master' password - the vault password
     * the vault password itself is encrypted with the master password of the CredentialService
     * a Vault stores credentials as child nodes in the repository encrypted with the vaults password
     * such a Vault can be created and removed easyly (e.g. for each tenant on tenant creation / removal)
     */
    private class Vault {

        protected final String id;
        protected final String path;
        protected final String title;
        /**
         * the repository path to use for ACL driven access rules check
         */
        protected final String referencePath;
        private final String vaultPassword;

        public Vault(Resource resource) {
            ValueMap values = resource.getValueMap();
            this.id = resource.getName();
            this.path = resource.getPath();
            this.title = values.get(ResourceUtil.JCR_TITLE, String.class);
            this.referencePath = values.get(PROP_REFERENCEPATH, String.class);
            this.vaultPassword = cryptoService.decrypt(values.get(PROP_ENCRYPTED_PASSWD, String.class), getMasterPassword());
        }

        @Nullable
        protected CredentialConfiguration getCredentials(@Nonnull final ResourceResolver resolver,
                                                         @Nonnull final String credentialsId) {
            Resource resource = resolver.getResource(this.path);
            if (resource != null) {
                resource = resource.getChild(credentialsId);
            }
            return resource != null ? new CredentialConfiguration(credentialsId, resource) : null;
        }

        /**
         * Stores or removes a credentials object.
         *
         * @param resolver      the current user session (for access rule check)
         * @param credentialsId the tídentifier - the name of the credentials node
         * @param values        the values to store - each 'encrypted...' has to be stored as encrypted property
         */
        protected void setCredentials(@Nonnull final ResourceResolver resolver,
                                      @Nonnull final String credentialsId, @Nullable final ValueMap values) {
        }

        /**
         * @param resolver the current user session (for access rule check)
         * @return the master password of the vault
         */
        protected String getVaultPassword(@Nonnull final ResourceResolver resolver) {
            return vaultPassword;
        }

        protected void setVaultPassword(@Nonnull final ResourceResolver resolver,
                                        @Nonnull final String oldVaultOrMasterPassword,
                                        @Nonnull final String newPassword) {
        }
    }

    /**
     * @param resolver the current user session (for access rule check)
     * @param vaultId  the internal name of the vault
     */
    protected Vault getVault(@Nonnull final ResourceResolver resolver,
                             @Nonnull final String vaultId) {
        Resource resource = resolver.getResource("/var/composum/platform/security/vault");
        if (resource != null) {
            resource = resource.getChild(vaultId);
        }
        return resource != null ? new Vault(resource) : null;
    }

    /**
     * @param resolver the current user session (for access rule check)
     * @param vaultId  the internal name of the vault - used also as repository node name
     * @param title    an optional - more readable - title of the vault
     */
    public void createVault(@Nonnull final ResourceResolver resolver,
                            @Nonnull final String vaultId, @Nullable final String title,
                            @Nonnull final String referencePath) {
    }

    /**
     * @param resolver the current user session (for access rule check)
     * @param vaultId  the internal name of the vault
     */
    public void deleteVault(@Nonnull final ResourceResolver resolver,
                            @Nonnull final String vaultId,
                            @Nonnull final String vaultOrMasterPassword) {
    }
}

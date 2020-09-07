package com.composum.platform.commons.credentials;

import org.apache.http.auth.AuthScope;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.mail.Authenticator;

/**
 * A systemwide credential store where you can retrieve credentials which are stored encrypted in the repository.
 * Each creadential pair has a systemwide unique ID, and might have a path configured, to which the user has to have
 * access, and/or a group, to which the user must belong.
 */
public interface CredentialService {

    /**
     * Type of credentials for use with {@link #initHttpContextCredentials(HttpClientContext, AuthScope, String, ResourceResolver)}.
     */
    public static final String TYPE_HTTP = "http";
    /**
     * Type of credentials for use with {@link #getMailAuthenticator(String, ResourceResolver)}.
     */
    public static final String TYPE_EMAIL = "email";

    /**
     * Initializes credentials for use with an {@link org.apache.http.client.HttpClient} for the given {authScope}.
     *
     * @param context             the HTTP client context to initialize
     * @param authScope           authentication scope
     * @param credentialIdOrToken ID for the credentials, usually a path from the credential root, e.g. /content/ist/testsites/testpages/localfullsite.
     *                            It can also start with a slash, but it is not recommended to do so, since that could be confused with an absolute path.
     *                            It is also possible to give a token returned by {@link #getAccessToken(String, ResourceResolver, String)}.
     * @param aclCheckResolver    a resolver that has to be able to resolve the aclpath stored in the credential as a security mechanism.
     * @throws RepositoryException      error accessing the repository or the aclpath could not be read with the aclCheckResolver
     * @throws IllegalArgumentException if the credentials do not exist or are of the wrong type
     * @throws IllegalStateException    if the service is not enabled
     */
    void initHttpContextCredentials(@Nonnull HttpClientContext context, @Nonnull AuthScope authScope,
                                    @Nonnull String credentialIdOrToken, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException, IllegalArgumentException, IllegalStateException;

    /**
     * Encodes a password with the master password.
     */
    String encodePassword(@Nonnull String password);

    boolean isEnabled();

    /**
     * Creates an {@link Authenticator} returning a {@link java.net.PasswordAuthentication} with the credentials.
     *
     * @param credentialIdOrToken ID for the credentials, usually a path from the credential root, e.g. content/ist/testsites/testpages/localfullsite.
     *                            It can also start with a slash, but it is not recommended to do so, since that could be confused with an absolute path.
     *                            It is also possible to give a token returned by {@link #getAccessToken(String, ResourceResolver, String)}.
     * @param aclCheckResolver    a resolver that has to be able to resolve the aclpath stored in the credential as a security mechanism.
     * @throws RepositoryException      error accessing the repository or the aclpath could not be read with the aclCheckResolver
     * @throws IllegalArgumentException if the credentials do not exist or are of the wrong type
     * @throws IllegalStateException    if the service is not enabled
     */
    Authenticator getMailAuthenticator(@Nonnull String credentialIdOrToken, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException, IllegalArgumentException, IllegalStateException;

    /**
     * Returns a token that can be used in place of a credentialId to access a credential for some time.
     * This is sometimes neccessary if a service needs credentials for retries - it won't have access to the
     * users resolver for the acl checks.
     *
     * @param aclCheckResolver a resolver that has to be able to resolve the aclpath stored in the credential as a security mechanism.
     * @throws RepositoryException      error accessing the repository or the aclpath could not be read with the aclCheckResolver
     * @throws IllegalArgumentException if the credentials do not exist or are of the wrong type
     * @throws IllegalStateException    if the service is not enabled
     */
    String getAccessToken(@Nonnull String credentialId, @Nullable ResourceResolver aclCheckResolver, @Nonnull String type) throws RepositoryException, IllegalArgumentException, IllegalStateException;

}

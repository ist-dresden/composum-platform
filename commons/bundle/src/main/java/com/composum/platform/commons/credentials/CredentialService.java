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
     * Initializes credentials for use with an {@link org.apache.http.client.HttpClient} for the given {authScope}.
     *
     * @param context          the HTTP client context to initialize
     * @param authScope        authentication scope
     * @param credentialId     ID for the credentials, usually a path from the credential root, e.g. /content/ist/testsites/testpages/localfullsite
     * @param aclCheckResolver a resolver that has to be able to resolve the aclpath stored in the credential as a security mechanism.
     */
    void initHttpContextCredentials(@Nonnull HttpClientContext context, @Nonnull AuthScope authScope,
                                    @Nonnull String credentialId, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException;

    /**
     * Encodes a password with the master password.
     */
    String encodePassword(@Nonnull String password);

    boolean isEnabled();

    /**
     * Creates an {@link Authenticator} returning a {@link java.net.PasswordAuthentication} with the credentials.
     *
     * @param credentialId     ID for the credentials, usually a path from the credential root, e.g. /content/ist/testsites/testpages/localfullsite
     * @param aclCheckResolver a resolver that has to be able to resolve the aclpath stored in the credential as a security mechanism.
     */
    Authenticator getMailAuthenticator(@Nonnull String credentialId, @Nullable ResourceResolver aclCheckResolver) throws RepositoryException;

}

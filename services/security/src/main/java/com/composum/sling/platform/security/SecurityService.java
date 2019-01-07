package com.composum.sling.platform.security;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.IOException;
import java.util.Map;

public interface SecurityService {

    void addJsonAcl(Session session, String jsonFilePath)
            throws RepositoryException, IOException;

    void addAcl(Session session, String path,
                String principalName, boolean allow, String[] privilegeKeys,
                Map restrictionKeys)
            throws RepositoryException;
}

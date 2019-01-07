package com.composum.sling.platform.security;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.value.StringValue;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Security Service"
        }
)
public class PlatformSecurityService implements SecurityService {

    @Override
    public void addJsonAcl(final Session session, final String jsonFilePath)
            throws RepositoryException, IOException {
        Node jsonFileNode = session.getNode(jsonFilePath);
        Property property = jsonFileNode.getNode(JcrConstants.JCR_CONTENT).getProperty(JcrConstants.JCR_DATA);
        try (InputStream stream = property.getBinary().getStream();
             Reader streamReader = new InputStreamReader(stream, UTF_8);
             JsonReader reader = new JsonReader(streamReader)) {
            addJsonAcl(session, reader);
        }
    }

    public void addJsonAcl(final Session session, final JsonReader reader)
            throws RepositoryException, IOException {
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.peek() != JsonToken.END_ARRAY) {
                addAclObject(session, reader);
            }
            reader.endArray();
        } else {
            addAclObject(session, reader);
        }
    }

    @SuppressWarnings("unchecked")
    protected void addAclObject(final Session session, final JsonReader reader)
            throws RepositoryException {
        final Gson gson = new Gson();
        final Map map = gson.fromJson(reader, Map.class);
        final String path = (String) map.get("path");
        if (StringUtils.isNotBlank(path)) {
            final List<Map> acl = (List<Map>) map.get("acl");
            if (acl != null) {
                addAclList(session, path, acl);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void addAclList(final Session session, final String path, final List<Map> list)
            throws RepositoryException {
        for (Map map : list) {
            String principal = (String) map.get("principal");
            if (StringUtils.isNotBlank(principal)) {
                final List<Map> acl = (List<Map>) map.get("acl");
                if (acl != null) {
                    for (Map rule : acl) {
                        boolean allow = "allow".equalsIgnoreCase((String) rule.get("type"));
                        Object object = rule.get("privileges");
                        String[] privileges;
                        if (object instanceof List) {
                            List<String> privList = (List<String>) object;
                            privileges = privList.toArray(new String[0]);
                        } else {
                            privileges = new String[]{(String) object};
                        }
                        Map<String, String> restrictions = (Map<String, String>) rule.get("restrictions");
                        addAcl(session, path, principal, allow, privileges,
                                restrictions != null ? restrictions : Collections.EMPTY_MAP);
                    }
                }
            }
        }
    }

    @Override
    public void addAcl(final Session session, final String path,
                       final String principalName, boolean allow, final String[] privilegeKeys,
                       final Map restrictionKeys)
            throws RepositoryException {
        final AccessControlManager acManager = session.getAccessControlManager();
        final PrincipalManager principalManager = ((JackrabbitSession) session).getPrincipalManager();
        final JackrabbitAccessControlList policy = AccessControlUtils.getAccessControlList(acManager, path);
        final Principal principal = principalManager.getPrincipal(principalName);
        final Privilege[] privileges = AccessControlUtils.privilegesFromNames(acManager, privilegeKeys);
        final Map<String, Value> restrictions = new HashMap<>();
        for (final Object key : restrictionKeys.keySet()) {
            restrictions.put((String) key, new StringValue((String) restrictionKeys.get(key)));
        }
        policy.addEntry(principal, privileges, allow, restrictions);
        acManager.setPolicy(path, policy);
    }
}

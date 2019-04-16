package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import com.google.common.collect.Iterators;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Iterator;

/**
 * This is a servlet that has been deployed to check the behaviour of the version storage wrt. permissions. It's kept
 * around for a while in case there are more things to clarify.
 * Results: a resolver can access the versions in /jcr:system/jcr:versionStorage only if it can access the path
 * in the default property of the version history. If a versionable is moved around, the default property is modified
 * automatically to match the new location. If a document is deleted, the default property of the version history is
 * unchanged and the jcr:versionableUuid is also not changed. The version history of deleted versionables still only accessible
 * if the resolver can read the path in the default property of the version history. (Caution: this means that
 * another user might be able read it if he comes to own that path later.)
 */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=ACL TEST",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/no" + "des/de" + "bug/checkacls",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET
        })
public class TemporaryCheckAclServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(TemporaryCheckAclServlet.class);

    @Reference
    private ResourceResolverFactory factory;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver adminRes = null;
        ResourceResolver anonRes = null;
        try {
            adminRes = factory.getAdministrativeResourceResolver(null);
            anonRes = factory.getResourceResolver(null);
            Session adminSession = adminRes.adaptTo(Session.class);
            Session anonSession = anonRes.adaptTo(Session.class);
            String p1 = "/content/ist/composum/home/incubator/keycloakAuth/jcr:content";
            String p2 = "/content/ist/composum/home/incubator/clientLibraries/jcr:content";
            AccessControlUtils.clear(adminSession, p1);
            AccessControlUtils.clear(adminSession, p2);
            AccessControlUtils.clear(adminSession, "/jcr:system/jcr:versionStorage");
            AccessControlUtils.clear(adminSession, "/jcr:system");
            // AccessControlUtils.denyAllToEveryone(adminSession, "/content/ist/composum/home/incubator");
            adminSession.save();
            anonSession.refresh(false);

            try {
                Iterator<Resource> it = anonRes.findResources("/jcr:root/jcr:system/jcr:versionStorage//*[jcr:like(@default,'/content/ist/composum/home/incubator/%')]", Query.XPATH);
                int size = Iterators.size(it);
                size = Iterators.size(adminRes.getResource("/jcr:system/jcr:versionStorage").listChildren());

                Resource r1 = anonRes.getResource(p1);
                Resource vr1 = ResourceUtil.getReferredResource(r1.getChild("jcr:versionHistory"));
                String vrp1 = vr1 != null ? vr1.getPath() : null;

                Resource r2 = anonRes.getResource(p2);
                Resource vr2 = ResourceUtil.getReferredResource(r2.getChild("jcr:versionHistory"));
                String vrp2 = vr2 != null ?  vr2.getPath() : null;

                Resource x = vr2;
                while (x != null) {
                    size = Iterators.size(x.listChildren());
                    x = x.getParent();
                }

                LOG.error("VR2", vr2);

                AccessControlUtils.denyAllToEveryone(adminSession, p2);
                adminSession.save();
                anonSession.refresh(false);

                it = anonRes.findResources("/jcr:root/jcr:system/jcr:versionStorage//*[jcr:like(@default,'/content/ist/composum/home/incubator/%')]", Query.XPATH);
                size = Iterators.size(it);

                r1 = anonRes.getResource(p1);
                vr1 = anonRes.getResource(vrp1);
                vr1 = ResourceUtil.getReferredResource(r1.getChild("jcr:versionHistory"));

                r2 = anonRes.getResource(p2);
                vr2 = anonRes.getResource(vrp2);
                vr2 = r2 != null ? ResourceUtil.getReferredResource(r2.getChild("jcr:versionHistory")) : null;

                LOG.error("VR2", vr2);
            } finally {
                AccessControlUtils.clear(adminSession, p1);
                AccessControlUtils.clear(adminSession, p2);
                AccessControlUtils.clear(adminSession, "/jcr:system/jcr:versionStorage");
                AccessControlUtils.clear(adminSession, "/jcr:system");
                AccessControlUtils.clear(adminSession, "/content/ist/composum/home/incubator");
                adminSession.save();
            }
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            adminRes.close();
            anonRes.close();
        }
    }
}

package com.composum.platform.services.setup;

import com.composum.sling.core.usermanagement.core.UserManagementService;
import com.composum.sling.nodes.service.SecurityService;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SetupHook implements InstallHook {

    private static final Logger LOG = LoggerFactory.getLogger(SetupHook.class);

    private static final String ADMINISTRATORS_GROUP = "administrators";

    private static final Map<String, List<String>> PLATFORM_GROUPS;

    static {
        PLATFORM_GROUPS = new LinkedHashMap<>();
        PLATFORM_GROUPS.put(ADMINISTRATORS_GROUP, Collections.singletonList(
                "admin"
        ));
    }

    @Override
    public void execute(InstallContext ctx) {
        switch (ctx.getPhase()) {
            case PREPARE:
                LOG.info("prepare: execute...");
                setupGroupsAndUsers(ctx);
                LOG.info("prepare: execute ends.");
                break;
            case INSTALLED:
                LOG.info("installed: execute...");
                setupAcls(ctx);
                LOG.info("installed: execute ends.");
                break;
        }
    }

    protected void setupGroupsAndUsers(InstallContext ctx) {
        UserManagementService userManagementService = getService(UserManagementService.class);
        try {
            JackrabbitSession session = (JackrabbitSession) ctx.getSession();
            UserManager userManager = session.getUserManager();
            for (Map.Entry<String, List<String>> entry : PLATFORM_GROUPS.entrySet()) {
                Group group = userManagementService.getOrCreateGroup(session, userManager, entry.getKey());
                if (group != null) {
                    for (String memberName : entry.getValue()) {
                        userManagementService.assignToGroup(session, userManager, memberName, group);
                    }
                }
            }
            session.save();
        } catch (RepositoryException rex) {
            LOG.error(rex.getMessage(), rex);
        }
    }

    protected void setupAcls(InstallContext ctx) {
        SecurityService securityService = getService(SecurityService.class);
        try {
            Session session = ctx.getSession();
            securityService.addJsonAcl(session, "/conf/composum/platform/security/everyone.json");
            session.save();
        } catch (RepositoryException | IOException rex) {
            LOG.error(rex.getMessage(), rex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> type) {
        Bundle serviceBundle = FrameworkUtil.getBundle(type);
        BundleContext serviceBundleContext = serviceBundle.getBundleContext();
        ServiceReference serviceReference = serviceBundleContext.getServiceReference(type.getName());
        return (T) serviceBundleContext.getService(serviceReference);
    }
}

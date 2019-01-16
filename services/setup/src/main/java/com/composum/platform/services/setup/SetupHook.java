package com.composum.platform.services.setup;

import com.composum.sling.core.service.RepositorySetupService;
import com.composum.sling.core.usermanagement.core.UserManagementService;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
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

@SuppressWarnings("Duplicates")
public class SetupHook implements InstallHook {

    private static final Logger LOG = LoggerFactory.getLogger(SetupHook.class);

    private static final String EVERYONE_ACLS = "/conf/composum/platform/security/acl/everyone.json";

    private static final String ADMINISTRATORS_GROUP = "administrators";

    public static final String PLATFORM_USERS_PATH = "composum/platform/";
    public static final String PLATFORM_SYSTEM_USERS_PATH = "system/composum/platform/";

    public static final String PLATFORM_ADMINISTRATORS = "composum-platform-administrators";
    public static final String PLATFORM_SERVICE_USER = "composum-platform-service";

    public static final Map<String, List<String>> PLATFORM_USERS;
    public static final Map<String, List<String>> PLATFORM_SYSTEM_USERS;
    public static final Map<String, List<String>> PLATFORM_GROUPS;

    static {
        PLATFORM_USERS = new LinkedHashMap<>();
        PLATFORM_SYSTEM_USERS = new LinkedHashMap<>();
        PLATFORM_SYSTEM_USERS.put(PLATFORM_SYSTEM_USERS_PATH + PLATFORM_SERVICE_USER, Collections.singletonList(
                PLATFORM_ADMINISTRATORS
        ));
        PLATFORM_GROUPS = new LinkedHashMap<>();
        PLATFORM_GROUPS.put(PLATFORM_USERS_PATH + PLATFORM_ADMINISTRATORS, Collections.singletonList(
                PLATFORM_SERVICE_USER
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
            for (Map.Entry<String, List<String>> entry : PLATFORM_SYSTEM_USERS.entrySet()) {
                Authorizable user = userManagementService.getOrCreateUser(session, userManager, entry.getKey(), true);
                if (user != null) {
                    for (String groupName : entry.getValue()) {
                        userManagementService.assignToGroup(session, userManager, user, groupName);
                    }
                }
            }
            session.save();
            for (Map.Entry<String, List<String>> entry : PLATFORM_USERS.entrySet()) {
                Authorizable user = userManagementService.getOrCreateUser(session, userManager, entry.getKey(), false);
                if (user != null) {
                    for (String groupName : entry.getValue()) {
                        userManagementService.assignToGroup(session, userManager, user, groupName);
                    }
                }
            }
            session.save();
        } catch (RepositoryException rex) {
            LOG.error(rex.getMessage(), rex);
        }
    }

    protected void setupAcls(InstallContext ctx) {
        RepositorySetupService setupService = getService(RepositorySetupService.class);
        try {
            Session session = ctx.getSession();
            setupService.addJsonAcl(session, EVERYONE_ACLS, null);
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

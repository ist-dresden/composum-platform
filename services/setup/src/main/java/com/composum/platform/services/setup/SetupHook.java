package com.composum.platform.services.setup;

import com.composum.sling.core.service.RepositorySetupService;
import com.composum.sling.core.usermanagement.core.UserManagementService;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.apache.jackrabbit.vault.packaging.PackageException;
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
import java.util.HashMap;
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
    public void execute(InstallContext ctx) throws PackageException {
        switch (ctx.getPhase()) {
            case PREPARE:
                LOG.info("prepare: execute...");
                setupGroupsAndUsers(ctx);
                LOG.info("prepare: execute ends.");
                break;
            case INSTALLED:
                LOG.info("installed: execute...");
                setupAcls(ctx);
                checkBundles(ctx);
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

    protected void checkBundles(InstallContext ctx) throws PackageException {
        checkBundles(ctx, new HashMap<String, String>() {{
            put("com.composum.platform.staging", "1.0.0.SNAPSHOT");
            put("com.composum.platform.security", "1.0.0.SNAPSHOT");
        }}, 2, 30);
    }

    protected void checkBundles(InstallContext ctx, Map<String, String> bundlesToCheck,
                                int waitToStartSeconds, int timeoutSeconds)
            throws PackageException {
        try {
            // wait to give the bundle installer a chance to install bundles
            Thread.sleep(waitToStartSeconds * 1000);
        } catch (InterruptedException ignore) {
        }
        LOG.info("Check bundles...");
        BundleContext bundleContext = FrameworkUtil.getBundle(ctx.getSession().getClass()).getBundleContext();
        int ready = 0;
        for (int i = 0; i < timeoutSeconds; i++) {
            ready = 0;
            for (Bundle bundle : bundleContext.getBundles()) {
                String version = bundlesToCheck.get(bundle.getSymbolicName());
                if (version != null && version.equals(bundle.getVersion().toString())
                        && bundle.getState() == Bundle.ACTIVE) {
                    ready++;
                }
            }
            if (ready == bundlesToCheck.size()) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
            }
        }
        if (ready < bundlesToCheck.size()) {
            LOG.error("Checked bundles not ready - installation failed!");
            throw new PackageException("bundles not ready");
        } else {
            LOG.info("Checked bundles are up and ready.");
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

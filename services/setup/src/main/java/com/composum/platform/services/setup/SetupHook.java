package com.composum.platform.services.setup;

import com.composum.sling.core.service.RepositorySetupService;
import com.composum.sling.core.setup.util.SetupUtil;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.vault.fs.io.Archive;
import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("Duplicates")
public class SetupHook implements InstallHook {

    private static final Logger LOG = LoggerFactory.getLogger(SetupHook.class);

    private static final String CONFIG_ACL = "/conf/composum/platform/security/acl";
    private static final String SERVICE_ACLS = CONFIG_ACL + "/service.json";
    private static final String LOGIN_ACLS = CONFIG_ACL + "/login.json";

    public static final String PLATFORM_SYSTEM_USERS_PATH = "system/composum/platform/";

    public static final String PLATFORM_SERVICE_USER = "composum-platform-service";

    public static final Map<String, List<String>> PLATFORM_USERS;
    public static final Map<String, List<String>> PLATFORM_SYSTEM_USERS;
    public static final Map<String, List<String>> PLATFORM_GROUPS;

    static {
        PLATFORM_USERS = new LinkedHashMap<>();
        PLATFORM_SYSTEM_USERS = new LinkedHashMap<>();
        PLATFORM_SYSTEM_USERS.put(PLATFORM_SYSTEM_USERS_PATH + PLATFORM_SERVICE_USER, Collections.emptyList());
        PLATFORM_GROUPS = new LinkedHashMap<>();
    }

    @Override
    public void execute(InstallContext ctx) throws PackageException {
        switch (ctx.getPhase()) {
            case PREPARE:
                LOG.info("prepare: execute...");
                SetupUtil.setupGroupsAndUsers(ctx, PLATFORM_GROUPS, PLATFORM_SYSTEM_USERS, PLATFORM_USERS);
                LOG.info("prepare: execute ends.");
                break;
            case INSTALLED:
                LOG.info("installed: execute...");
                setupAcls(ctx);
                // updateNodeTypes should be the last actions since we need a session.save() there.
                updateNodeTypes(ctx);
                LOG.info("installed: execute ends.");
                break;
        }
    }

    protected void setupAcls(InstallContext ctx) throws PackageException {
        RepositorySetupService setupService = SetupUtil.getService(RepositorySetupService.class);
        try {
            Session session = ctx.getSession();
            setupService.addJsonAcl(session, SERVICE_ACLS, null);
            setupService.addJsonAcl(session, LOGIN_ACLS, null);
            session.save();
        } catch (Exception rex) {
            LOG.error(rex.getMessage(), rex);
            throw new PackageException(rex);
        }
    }

    protected void updateNodeTypes(InstallContext ctx) throws PackageException {
        try {
            Session session = ctx.getSession();
            NodeTypeManager nodeTypeManager = session.getWorkspace().getNodeTypeManager();
            NodeType releaseType = nodeTypeManager.getNodeType("cpl:releaseRoot");
            if (!updateNeeded(releaseType, session, nodeTypeManager)) {
                LOG.info("No node type update needed.");
                return;
            }
            Archive archive = ctx.getPackage().getArchive();
            try (InputStream stream = archive.openInputStream(archive.getEntry("/META-INF/vault/nodetypes.cnd"))) {
                if (stream != null) {
                    InputStreamReader cndReader = new InputStreamReader(stream);
                    CndImporter.registerNodeTypes(cndReader, session, true);
                }
            }
            try {
                nodeTypeManager.unregisterNodeType("cpl:releaseConfig");
            } catch (NoSuchNodeTypeException e) {
                // OK.
            } catch (RepositoryException e) { // can happen when it's still used in pages
                LOG.warn("Could not deregister cpl:releaseConfig, probably since it's still used", e);
            }
        } catch (Exception rex) {
            LOG.error(rex.getMessage(), rex);
            throw new PackageException(rex);
        }
    }

    protected boolean updateNeeded(NodeType releaseType, Session session, NodeTypeManager nodeTypeManager) throws RepositoryException {
        try {
            nodeTypeManager.getNodeType("cpl:releaseConfig");
            return true; // should be removed
        } catch (NoSuchNodeTypeException e) {
            LOG.info("OK, obsolete cpl:releaseConfig is not present, but cpl:releaseRoot is");
        }
        boolean hasReleaseChangeNumber = Arrays.asList(releaseType.getPropertyDefinitions()).stream()
                .anyMatch((pd) -> "cpl:releaseChangeNumber".equals(pd.getName()));
        if (!hasReleaseChangeNumber) {
            LOG.info("Need to update since cpl:releaseChangeNumber " +
                    "is not present on cpl:releaseRoot");
        }
        return !hasReleaseChangeNumber;
    }
}

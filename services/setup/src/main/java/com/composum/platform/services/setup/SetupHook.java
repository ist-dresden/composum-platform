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

import javax.jcr.Session;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings("Duplicates")
public class SetupHook implements InstallHook {

    private static final Logger LOG = LoggerFactory.getLogger(SetupHook.class);

    public static final String PLATFORM_VERSION = "1.0.0.SNAPSHOT";

    private static final String EVERYONE_ACLS = "/conf/composum/platform/security/acl/everyone.json";
    private static final String ADMIN_ACLS = "/conf/composum/platform/security/acl/administrators.json";

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
                SetupUtil.setupGroupsAndUsers(ctx, PLATFORM_GROUPS, PLATFORM_SYSTEM_USERS, PLATFORM_USERS);
                LOG.info("prepare: execute ends.");
                break;
            case INSTALLED:
                LOG.info("installed: execute...");
                SetupUtil.checkBundles(ctx, new HashMap<String, String>() {{
                    put("com.composum.platform.staging", PLATFORM_VERSION);
                    put("com.composum.platform.security", PLATFORM_VERSION);
                }}, 2, 30);
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
            setupService.addJsonAcl(session, EVERYONE_ACLS, null);
            setupService.addJsonAcl(session, ADMIN_ACLS, null);
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
            NodeType releaseConfigType = nodeTypeManager.getNodeType("cpl:releaseConfig");
            NodeDefinition[] childdefs = releaseConfigType.getChildNodeDefinitions();
            Optional<NodeDefinition> releasesNodeDef = Arrays.asList(childdefs).stream().filter(c -> c.getName().equals("cpl:releases")).findFirst();
            if (!releasesNodeDef.isPresent() || releasesNodeDef.get().isMandatory()) {
                LOG.warn("cpl:releaseConfig has mandatory cpl:releases even after package installation - updating it.");

                Archive archive = ctx.getPackage().getArchive();
                try (InputStream stream = archive.openInputStream(archive.getEntry("/META-INF/vault/nodetypes.cnd"))) {
                    InputStreamReader cndReader = new InputStreamReader(stream);
                    CndImporter.registerNodeTypes(cndReader, session, true);
                }

                childdefs = releaseConfigType.getChildNodeDefinitions();
                releasesNodeDef = Arrays.asList(childdefs).stream().filter(c -> c.getName().equals("cpl:releases")).findFirst();

                if (!releasesNodeDef.isPresent() || releasesNodeDef.get().isMandatory()) {
                    LOG.error("cpl:releaseConfig still has a mandatory cpl:releases even after package installation!");
                }
            } else {
                LOG.info("OK: cpl:releaseConfig's cpl:releases is optional");
            }
        } catch (Exception rex) {
            LOG.error(rex.getMessage(), rex);
            throw new PackageException(rex);
        }
    }

}

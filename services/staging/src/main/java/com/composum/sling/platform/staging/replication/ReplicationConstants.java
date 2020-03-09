package com.composum.sling.platform.staging.replication;

import java.util.regex.Pattern;

/**
 * Constant values related exclusively to the replication
 */
public interface ReplicationConstants {

    /**
     * Parameter for a changed path.
     */
    String PARAM_PATH = "path";

    /**
     * Parameter that points to the release root. Should be deliberately used as last part in the request,
     * to easily ensure that the whole request was transmitted.
     */
    String PARAM_RELEASEROOT = "releaseRoot";

    /**
     * The path of the content that is actually replicated - can be releaseRoot or a subpath.
     */
    String PARAM_SOURCEPATH = "sourcePath";

    /**
     * The path where the content below {@link #PARAM_SOURCEPATH} us placed - if different than
     * {@link #PARAM_SOURCEPATH} this might imply reference transformation.
     */
    String PARAM_TARGETPATH = "targetPath";

    /**
     * A parameter for a content path that is replicated - exact meaning depends on the context.
     */
    String PARAM_CONTENTPATH = "targetPath";


    /**
     * A JSON-field that contains the whole of {@link ReplicationPaths}.
     */
    String PARAM_REPLICATIONPATHS = "replicationPaths";

    /**
     * Parameter for {@link ChildrenOrderInfo}s.
     */
    String PARAM_CHILDORDERINGS = "childOrderings";

    /**
     * Parameter for {@link NodeAttributeComparisonInfo}s.
     */
    String PARAM_ATTRIBUTEINFOS = "attributeInfos";

    /**
     * Pattern the {@link #PARAM_UPDATEID} has to comply with.
     */
    Pattern PATTERN_UPDATEID = Pattern.compile("upd-[a-zA-Z0-9]{12}");

    /**
     * the resource type (RT) of the configuration nodes parent resource
     */
    String RT_REPLICATION_SETUP = "composum/platform/replication/setup";

}

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

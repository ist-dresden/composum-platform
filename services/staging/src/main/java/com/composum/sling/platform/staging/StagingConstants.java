package com.composum.sling.platform.staging;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static org.apache.jackrabbit.JcrConstants.*;

/**
 * Constants related to the staging mechanisms. See also the nodetypes.cnd .
 */
public interface StagingConstants {

    /**
     * Prefix for the release number when added as a label to the released version of a versionable. i.e. composum-release-r3.1.4 .
     * This is added as an additional mark to be able to easily create queries into version store restricted to the contents of
     * one release.
     */
    public static final String RELEASE_LABEL_PREFIX = "composum-release-";

    /**
     * Mixin for the versionable node that contains the structure of all releases in a subnode cpl:releases.
     * We also save the full path at which the release node was created in cpl:pathOnCheckin.
     * The subnode cpl:releases
     */
    final String TYPE_MIX_RELEASE_CONFIG = "cpl:releaseConfig";

    /**
     * The node containing the releases below {@link #TYPE_MIX_RELEASE_CONFIG}.
     */
    final String NODE_RELEASES = "cpl:releases";

    /**
     * Mixin that makes a node a root of a release. This mainly ensures easy location of the release
     * roots and that a jcr:content node with {@link #TYPE_MIX_RELEASE_CONFIG} is always present.
     */
    final String TYPE_MIX_RELEASE_ROOT = "cpl:releaseRoot";

    /**
     * References a version within the release tree.
     *
     * @see #PROP_VERSION
     * @see #PROP_VERSIONABLEUUID
     * @see #PROP_VERSIONHISTORY
     */
    final String TYPE_VERSIONREFERENCE = "cpl:VersionReference";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the nt:version, as in the jcr:baseVersion attribute of mix:versionable.
     */
    final String PROP_VERSION = "cpl:version";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the nt:versionHistory - the jcr:versionHistory attribute in mix:versionable.
     */
    final String PROP_VERSIONHISTORY = "cpl:versionHistory";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the mix:versionable, as in the jcr:versionableUuid of nt:version.
     */
    final String PROP_VERSIONABLEUUID = "cpl:versionableUuid";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: if true this reference is ignored. If not set, this is evaluated as false.
     */
    final String PROP_DEACTIVATED = "cpl:deactivated";

    /**
     * "Releasenumber" or key of the current release : {@value #CURRENT_RELEASE}. This also serves as the "current release" (the release that's in construction and will be used as default preview)
     * below {@link #NODE_RELEASES}.
     */
    final String CURRENT_RELEASE = "cpl:current";

    /**
     * Nodename below a release node or {@link #CURRENT_RELEASE} that contains a copy of the working tree of the site
     * with versionable nodes replaced with {@link #TYPE_VERSIONREFERENCE}.<br/>
     * <b>Caution</b>: Don't touch this node and its subnodes - always use the * {@link StagingReleaseManager} for that!
     */
    final String NODE_RELEASE_ROOT = "root";

    /**
     * Nodename below a release node or {@link #CURRENT_RELEASE} that can contain metadata for a release.
     */
    final String NODE_RELEASE_METADATA = "metaData";

    /** maps the frozen property types to their normal names. */
    final Map<String, String> FROZEN_PROP_NAMES_TO_REAL_NAMES = ImmutableMap.of(
            JCR_FROZENPRIMARYTYPE, JCR_PRIMARYTYPE,
            JCR_FROZENUUID, JCR_UUID,
            JCR_FROZENMIXINTYPES, JCR_MIXINTYPES);

    /** maps the frozen property types to their normal names. */
    final Map<String, String> REAL_PROPNAMES_TO_FROZEN_NAMES = ImmutableMap.of(
            JCR_PRIMARYTYPE, JCR_FROZENPRIMARYTYPE,
            JCR_UUID, JCR_FROZENUUID,
            JCR_MIXINTYPES, JCR_FROZENMIXINTYPES);

}

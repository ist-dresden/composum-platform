package com.composum.sling.platform.staging;

/**
 * Constants related to the staging mechanisms. See also the nodetypes.cnd .
 */
public interface StagingConstants {

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
     * Nodename for the "current release" (the release that's in construction and will be used as default preview)
     * below {@link #NODE_RELEASES}.
     */
    final String NODE_CURRENT_RELEASE = "cpl:current";

    /**
     * Nodename below a release node or {@link #NODE_CURRENT_RELEASE} that contains a copy of the working tree of the site
     * with versionable nodes replaced with {@link #TYPE_VERSIONREFERENCE}.<br/>
     * <b>Caution</b>: Don't touch this node and its subnodes - always use the * {@link com.composum.sling.platform.staging.service.StagingReleaseManager} for that!
     */
    final String NODE_RELEASE_ROOT = "root";

    /**
     * Nodename below a release node or {@link #NODE_CURRENT_RELEASE} that can contain metadata for a release.
     */
    final String NODE_RELEASE_METADATA = "metaData";

}

package com.composum.sling.platform.staging;

import com.google.common.collect.ImmutableBiMap;

import static org.apache.jackrabbit.JcrConstants.JCR_FROZENMIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENUUID;
import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;

/**
 * Constants related to the staging mechanisms. See also the nodetypes.cnd .
 */
public interface StagingConstants {

    /**
     * Prefix for the release number when added as a label to the released version of a versionable. i.e. composum-release-r3.1.4 .
     * This is added as an additional mark to be able to easily create queries into version store restricted to the contents of
     * one release.
     */
    static String RELEASE_LABEL_PREFIX = "composum-release-";

    /**
     * The release data is saved below {@value RELEASE_ROOT_PATH} + content path of the release root + {@value #NODE_RELEASES}, e.g.
     * for /content/ist/composum it is in /var/composum/content/ist/composum/cpl:releaseRoot .
     */
    String RELEASE_ROOT_PATH = "/var/composum";

    /** The node containing the releases - see {@value #RELEASE_ROOT_PATH}. */
    String NODE_RELEASES = "cpl:releases";

    /**
     * Saves the path of the release root when it was created on the {@value #NODE_RELEASES}. This is currently not used, but reserved for
     * handling of moving a release root in the JCR tree.
     */
    String PROP_RELEASE_ROOT_HISTORY = "cpl:releaseRootHistory";

    /**
     * Marker-mixin that makes a node a root of a release.
     */
    String TYPE_MIX_RELEASE_ROOT = "cpl:releaseRoot";

    /**
     * References a version within the release tree.
     *
     * @see #PROP_VERSION
     * @see #PROP_VERSIONABLEUUID
     * @see #PROP_VERSIONHISTORY
     */
    String TYPE_VERSIONREFERENCE = "cpl:VersionReference";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the nt:version, as in the jcr:baseVersion attribute of mix:versionable.
     */
    String PROP_VERSION = "cpl:version";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the nt:versionHistory - the jcr:versionHistory attribute in mix:versionable.
     */
    String PROP_VERSIONHISTORY = "cpl:versionHistory";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: the jcr:uuid of the mix:versionable, as in the jcr:versionableUuid of nt:version.
     */
    String PROP_VERSIONABLEUUID = "cpl:versionableUuid";

    /**
     * Property of {@link #TYPE_VERSIONREFERENCE}: if true this reference is ignored. If not set, this is evaluated as false.
     */
    String PROP_DEACTIVATED = "cpl:deactivated";


    /** Property of {@link #TYPE_VERSIONREFERENCE}: when was the last activation */
    String PROP_LAST_ACTIVATED = "lastActivated";

    /** Property of {@link #TYPE_VERSIONREFERENCE}: who did the last activation */
    String PROP_LAST_ACTIVATED_BY = "lastActivatedBy";

    /** Property of {@link #TYPE_VERSIONREFERENCE}: when was the last deactivation */
    String PROP_LAST_DEACTIVATED = "lastDeactivated";

    /** Property of {@link #TYPE_VERSIONREFERENCE}: who did the last deactivation */
    String PROP_LAST_DEACTIVATED_BY = "lastDeactivatedBy";

    /** Boolean property of release node that tells whether the release is closed. If not set we use false. */
    String PROP_CLOSED = "closedRelease";

    /** Property of release node: the Uuid of the previous release from which this one was copied. */
    String PROP_PREVIOUS_RELEASE_UUID = "previousReleaseUuid";

    /**
     * "Releasenumber" or key of the current release : {@value #CURRENT_RELEASE}. This also serves as the "current release" (the release that's in construction and will be used as default preview)
     * below {@link #NODE_RELEASES}.
     */
    String CURRENT_RELEASE = "current";

    /**
     * Nodename below a release node or {@link #CURRENT_RELEASE} that contains a copy of the working tree of the site
     * with versionable nodes replaced with {@link #TYPE_VERSIONREFERENCE}.<br/>
     * <b>Caution</b>: Don't touch this node and its subnodes - always use the * {@link StagingReleaseManager} for that!
     */
    String NODE_RELEASE_ROOT = "root";

    /**
     * Nodename below a release node or {@link #CURRENT_RELEASE} that can contain metadata for a release.
     */
    String NODE_RELEASE_METADATA = "metaData";

    /** Maps the frozen property types to their normal names. */
    ImmutableBiMap<String, String> FROZEN_PROP_NAMES_TO_REAL_NAMES = ImmutableBiMap.of(
            JCR_FROZENPRIMARYTYPE, JCR_PRIMARYTYPE,
            JCR_FROZENUUID, JCR_UUID,
            JCR_FROZENMIXINTYPES, JCR_MIXINTYPES);

    /** Maps the frozen property types to their normal names. */
    ImmutableBiMap<String, String> REAL_PROPNAMES_TO_FROZEN_NAMES = FROZEN_PROP_NAMES_TO_REAL_NAMES.inverse();

}

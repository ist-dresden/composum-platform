package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.util.List;

/**
 * Data structure with metadata information about a release. This must only be created within the {@link StagingReleaseManager}.
 */
public interface Release {

    /**
     * The UUID of the top node with the release data. You can store this as property type {@link javax.jcr.PropertyType#REFERENCE}
     * somewhere to make sure a published release is not deleted.
     */
    @NotNull
    String getUuid();

    /**
     * Release number (JCR compatible) of the release: this is automatically created with {@link ReleaseNumberCreator}
     * and will be something like r4 or r2.4.5 . The current release is called {@value StagingConstants#CURRENT_RELEASE}.
     */
    @NotNull
    String getNumber();

    /**
     * Returns the label that is set on the versions, additionally to being stored in the content copy.
     * This is {@value StagingConstants#RELEASE_LABEL_PREFIX}{number} - except for the current
     * release where it's called {@value StagingConstants#RELEASE_LABEL_PREFIX}current,
     * since a label must not contain a colon.
     */
    @NotNull
    default String getReleaseLabel() {
        return StagingConstants.RELEASE_LABEL_PREFIX + getNumber();
    }

    /**
     * Path to internal release node. This is an unique identifier for the release, but use sparingly since this
     * is implementation dependent.
     */
    @NotNull
    String getPath();

    /**
     * The resource that is the top of the working tree - a {@value StagingConstants#TYPE_MIX_RELEASE_ROOT}.
     */
    @NotNull
    Resource getReleaseRoot();

    /**
     * The resource that contains metadata for this release. This is not touched by the {@link StagingReleaseManager}
     * and can be freely used to store additional metadata. If you want to change it, just retrieve the Release object
     * and write on the resource returned here - the {@link StagingReleaseManager} does not care about its contents.
     */
    @NotNull
    Resource getMetaDataNode();

    /**
     * An UID that changes on each release content change. If it's constant, you can rely on the release content
     * being the same. (Unless for changes not using the {@link StagingReleaseManager}, which you shouldn't do,
     * of course. At least not without calling {@link #bumpReleaseChangeNumber(Release)}, too.)
     */
    @NotNull
    String getChangeNumber();

    /**
     * The marks that point to this release. Each mark can only point to exactly one release.
     */
    @NotNull
    List<String> getMarks();

    /**
     * If true the {@link StagingReleaseManager} will refuse to change the releases contents.
     */
    boolean isClosed();

    /**
     * Returns the release from which this release was created.
     */
    @Nullable
    Release getPreviousRelease() throws RepositoryException;

    /**
     * Checks whether the given path is in the range of the release root. This does not check whether the resource actually exists.
     *
     * @param path an absolute path
     * @return true if it's within the tree spanned by the release root.
     */
    boolean appliesToPath(@Nullable String path);

    /**
     * Maps the relative path to the absolute path ( {@link #getReleaseRoot()} + '/' + relativePath ) ; if it's
     * already an absolute path into the release this returns it unmodified. If it's a path into the interal
     * release store, it's transformed to the absolute path into the content that corresponds to that path.
     * If it does not belong to the release at all, an {@link IllegalArgumentException} is thrown. For an empty
     * path, the release root is returned.
     */
    @NotNull
    String absolutePath(@Nullable String path) throws IllegalArgumentException;

    /**
     * Compares the releaseRoot and releaseNode paths.
     */
    @Override
    boolean equals(Object o);

    /**
     * Returns information about the activation of a versionable at relativePath.
     */
    @Nullable
    VersionReference versionReference(@Nullable String relativePath);
}

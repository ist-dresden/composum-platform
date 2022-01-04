package com.composum.sling.platform.staging;

import org.jetbrains.annotations.NotNull;

/**
 * Controls when a {@link com.composum.sling.platform.staging.StagingResourceResolver} applies the release mapping,
 * and when it just passes on the current resources.
 */
public interface ReleaseMapper {

    /**
     * Returns true if the release mapping should be applied to the given (absolute) path, accessed from the given URI.
     * If it returns false for a path inside a release, it must return false for all subpaths. Otherwise
     * the behaviour of {@link com.composum.sling.platform.staging.impl.StagingResourceResolver} etc. is undefined.
     */
    @Deprecated
    boolean releaseMappingAllowed(String path, String uri);

    /**
     * Returns true if the release mapping should be applied to the given (absolute) path.
     * If it returns false for a path inside a release, it must return false for all subpaths. Otherwise
     * the behaviour of {@link com.composum.sling.platform.staging.impl.StagingResourceResolver} etc. is undefined.
     */
    boolean releaseMappingAllowed(@NotNull String path);

    public static final ReleaseMapper ALLPERMISSIVE = new ReleaseMapper() {
        @Override
        public boolean releaseMappingAllowed(String path, String uri) {
            return true;
        }

        @Override
        public boolean releaseMappingAllowed(@NotNull String path) {
            return true;
        }
    };

}

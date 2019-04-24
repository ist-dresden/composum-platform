package com.composum.sling.platform.staging;

import javax.annotation.Nonnull;

/**
 * Controls when a {@link com.composum.sling.platform.staging.StagingResourceResolver} applies the release mapping,
 * and when it just passes on the current resources.
 */
public interface ReleaseMapper {

    /** Returns true if the release mapping should be applied to the given (absolute) path, accessed from the given URI. */
    @Deprecated
    boolean releaseMappingAllowed(String path, String uri);

    /** Returns true if the release mapping should be applied to the given (absolute) path. */
    boolean releaseMappingAllowed(@Nonnull String path);

    public static final ReleaseMapper ALLPERMISSIVE = new ReleaseMapper() {
        @Override
        public boolean releaseMappingAllowed(String path, String uri) {
            return true;
        }

        @Override
        public boolean releaseMappingAllowed(@Nonnull String path) {
            return true;
        }
    };

}

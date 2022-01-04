package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.core.util.XSS;
import com.composum.sling.platform.staging.replication.postprocess.MovePostprocessor;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.sling.api.SlingHttpServletRequest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;

/**
 * Contains information necessary to translate paths during replication: the release root and the source and target path.
 */
public class ReplicationPaths {

    @NotNull
    private final String releaseRoot;

    @Nullable
    private final String sourcePath;

    @Nullable
    private final String targetPath;

    @Nullable
    private String contentPath;

    private transient MovePostprocessor movePostprocessor;

    public ReplicationPaths(@NotNull String releaseRoot, @Nullable String sourcePath, @Nullable String targetPath, @Nullable String contentPath) {
        this.releaseRoot = Objects.requireNonNull(StringUtils.trimToNull(releaseRoot));
        this.sourcePath = StringUtils.trimToNull(sourcePath);
        this.targetPath = StringUtils.trimToNull(targetPath);
        this.contentPath = trimToOrigin(contentPath);
        if (this.sourcePath != null && !SlingResourceUtil.isSameOrDescendant(this.releaseRoot, this.sourcePath)) {
            throw new IllegalArgumentException("Source path must be descendant of release root.");
        }
    }

    /**
     * Returns the intersection of path and {@link #getOrigin()}.
     * If path is contained in {@link #getOrigin()}, it is returned unchanged. If it contains {@link #getOrigin()}, {@link #getOrigin()} is returned.
     * Otherwise we return null.
     */
    @Nullable
    public String trimToOrigin(@Nullable String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        if (SlingResourceUtil.isSameOrDescendant(getOrigin(), path)) {
            return path;
        }
        if (SlingResourceUtil.isSameOrDescendant(path, getOrigin())) {
            return getOrigin();
        }
        return null; // no common subpaths
    }

    public ReplicationPaths(Map<String, ?> params) {
        this((String) params.get(PARAM_RELEASEROOT), (String) params.get(PARAM_SOURCEPATH),
                (String) params.get(PARAM_TARGETPATH), (String) params.get(PARAM_CONTENTPATH));
    }

    public ReplicationPaths(@NotNull SlingHttpServletRequest request) {
        this(XSS.filter(request.getParameter(PARAM_RELEASEROOT)),
                XSS.filter(request.getParameter(PARAM_SOURCEPATH)),
                XSS.filter(request.getParameter(PARAM_TARGETPATH)),
                XSS.filter(request.getRequestPathInfo().getSuffix()));
    }

    /**
     * Only creates ReplicationPaths if the mandatory releaseRoot is set.
     */
    @Nullable
    public static ReplicationPaths optional(@NotNull SlingHttpServletRequest request) {
        if (StringUtils.isNotBlank(request.getParameter(PARAM_RELEASEROOT))) {
            return new ReplicationPaths(request);
        }
        return null;
    }

    /**
     * The release root - normally the site that is replicated.
     */
    @NotNull
    public String getReleaseRoot() {
        return releaseRoot;
    }

    /**
     * Optionally a path within the {@link #getReleaseRoot()} that is replicated. If this is not set, the whole release is replicated.
     */
    @Nullable
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Optionally, a path where the {@link #getSourcePath()} (or the whole {@link #getReleaseRoot()} if that isn't set) is replicated.
     * If not set, each path is replicated as is.
     */
    @Nullable
    public String getTargetPath() {
        return targetPath;
    }

    /**
     * A path within the {@link #getSourcePath()} / {@link #getReleaseRoot()} that is replicated / compared / whatever.
     * The meaning of this path depends on the context. If was outside {@link #getOrigin()}, it is trimmed to the origin
     */
    @Nullable
    public String getContentPath() {
        return contentPath;
    }

    /**
     * Sets the {@link #getContentPath()}.
     */
    public void setContentPath(@Nullable String contentPath) {
        this.contentPath = trimToOrigin(contentPath);
    }

    @NotNull
    public MovePostprocessor getMovePostprocessor() {
        if (movePostprocessor == null) {
            movePostprocessor = new MovePostprocessor(getOrigin(), getTargetPath());
        }
        return movePostprocessor;
    }

    /**
     * Translates a path on the source side to the target side.
     *
     * @throws IllegalArgumentException if the path is not within {@link #getSourcePath()} / {@link #getReleaseRoot()}
     */
    @NotNull
    public String translate(@NotNull String path) {
        String res = Objects.requireNonNull(path);
        String origin = getOrigin();
        if (!SlingResourceUtil.isSameOrDescendant(origin, path)) {
            throw new IllegalArgumentException("Path is outside of source range: " + path);
        }
        if (getTargetPath() != null) {
            String relpath = SlingResourceUtil.relativePath(origin, path);
            res = SlingResourceUtil.appendPaths(targetPath, relpath);
        }
        return Objects.requireNonNull(res);
    }

    /**
     * Translates a path on the target side to the source side.
     *
     * @throws IllegalArgumentException if the path is not within {@link #getSourcePath()} / {@link #getReleaseRoot()}
     */
    @NotNull
    public String inverseTranslate(@NotNull String path) {
        String res = Objects.requireNonNull(path);
        String origin = getOrigin();
        if (getTargetPath() != null) {
            if (!SlingResourceUtil.isSameOrDescendant(getTargetPath(), path)) {
                throw new IllegalArgumentException("Path is outside of target range: " + path);
            }
            String relpath = SlingResourceUtil.relativePath(getTargetPath(), path);
            res = SlingResourceUtil.appendPaths(origin, relpath);
        }
        return Objects.requireNonNull(res);
    }

    /**
     * Function that calls {@link #translate(String)} and adds an additional {offset} at the start, if that's given.
     */
    @NotNull
    public Function<String, String> translateMapping(@Nullable String offset) {
        return (path) -> {
            String translated = translate(path);
            if (StringUtils.isNotBlank(offset)) {
                translated = SlingResourceUtil.appendPaths(offset, translated);
            }
            return translated;
        };
    }

    /**
     * Function that removes an additional {offset} from the start, if that's given, and calls {@link #inverseTranslate(String)}.
     */
    @NotNull
    public Function<String, String> inverseTranslateMapping(@Nullable String offset) {
        return (path) -> {
            String offsetRemoved = path;
            if (offset != null) {
                if (!SlingResourceUtil.isSameOrDescendant(offset, path)) {
                    throw new IllegalArgumentException("Path is outside of target range: " + path);
                }
                offsetRemoved = StringUtils.prependIfMissing(SlingResourceUtil.relativePath(offset, offsetRemoved), "/");
            }
            String res = inverseTranslate(offsetRemoved);
            return res;
        };
    }

    /**
     * The origin: {@link #getSourcePath()} if not null, else {@link #getReleaseRoot()}.
     */
    @NotNull
    public String getOrigin() {
        return getSourcePath() != null ? getSourcePath() : getReleaseRoot();
    }

    /**
     * The destination: {@link #getTargetPath()} if not null, else the {@link #getOrigin()}.
     */
    @NotNull
    public String getDestination() {
        return getTargetPath() != null ? getTargetPath() : getReleaseRoot();
    }

    /**
     * True if the configuration requires remapping paths.
     */
    public boolean isMove() {
        return getTargetPath() != null && !getTargetPath().equals(getOrigin());
    }

    /**
     * Adds our values (except {@link #getContentPath()} which is usually transmitted as suffix) to a form.
     */
    public void addToForm(List<NameValuePair> form) {
        form.add(new BasicNameValuePair(ReplicationConstants.PARAM_RELEASEROOT, getReleaseRoot()));
        if (getSourcePath() != null) {
            form.add(new BasicNameValuePair(ReplicationConstants.PARAM_SOURCEPATH, getSourcePath()));
        }
        if (getTargetPath() != null) {
            form.add(new BasicNameValuePair(ReplicationConstants.PARAM_TARGETPATH, getTargetPath()));
        }
    }

    /**
     * Adds our values (except {@link #getContentPath()} which is usually transmitted as suffix) as parameter to an URI.
     */
    @NotNull
    public URIBuilder addToUriBuilder(@NotNull URIBuilder uriBuilder) {
        uriBuilder.addParameter(ReplicationConstants.PARAM_RELEASEROOT, getReleaseRoot());
        if (getSourcePath() != null) {
            uriBuilder.addParameter(ReplicationConstants.PARAM_SOURCEPATH, getSourcePath());
        }
        if (getTargetPath() != null) {
            uriBuilder.addParameter(ReplicationConstants.PARAM_TARGETPATH, getTargetPath());
        }
        return uriBuilder;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ReplPaths{");
        sb.append("releaseRoot='").append(releaseRoot).append('\'');
        if (null != sourcePath) {
            sb.append(", sourcePath='").append(sourcePath).append('\'');
        }
        if (targetPath != null) {
            sb.append(", targetPath='").append(targetPath).append('\'');
        }
        if (contentPath != null) {
            sb.append(", contentPath='").append(contentPath).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }

}

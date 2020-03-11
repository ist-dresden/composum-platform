package com.composum.sling.platform.staging.replication;

import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.core.util.XSS;
import com.composum.sling.platform.staging.replication.postprocess.MovePostprocessor;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.apache.sling.api.SlingHttpServletRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.platform.staging.replication.ReplicationConstants.*;

/**
 * Contains information necessary to translate paths during replication: the release root and the source and target path.
 */
public class ReplicationPaths {

    @Nonnull
    private final String releaseRoot;

    @Nullable
    private final String sourcePath;

    @Nullable
    private final String targetPath;

    @Nullable
    private final String contentPath;

    private transient MovePostprocessor movePostprocessor;

    public ReplicationPaths(@Nonnull String releaseRoot, @Nullable String sourcePath, @Nullable String targetPath, @Nullable String contentPath) {
        this.releaseRoot = Objects.requireNonNull(StringUtils.trimToNull(releaseRoot));
        this.sourcePath = StringUtils.trimToNull(sourcePath);
        this.targetPath = StringUtils.trimToNull(targetPath);
        this.contentPath = StringUtils.trimToNull(contentPath);
        if (this.sourcePath != null && !SlingResourceUtil.isSameOrDescendant(this.releaseRoot, this.sourcePath)) {
            throw new IllegalArgumentException("Source path must be descendant of release root.");
        }
        if (this.contentPath != null && !SlingResourceUtil.isSameOrDescendant(getOrigin(), this.contentPath)) {
            throw new IllegalArgumentException("Content path is outside of source range: " + contentPath);
        }
    }

    public ReplicationPaths(Map<String, ?> params) {
        this((String) params.get(PARAM_RELEASEROOT), (String) params.get(PARAM_SOURCEPATH),
                (String) params.get(PARAM_TARGETPATH), (String) params.get(PARAM_CONTENTPATH));
    }

    public ReplicationPaths(@Nonnull SlingHttpServletRequest request) {
        this(XSS.filter(request.getParameter(PARAM_RELEASEROOT)),
                XSS.filter(request.getParameter(PARAM_SOURCEPATH)),
                XSS.filter(request.getParameter(PARAM_TARGETPATH)),
                XSS.filter(request.getRequestPathInfo().getSuffix()));
    }

    /**
     * Only creates ReplicationPaths if the mandatory releaseRoot is set.
     */
    @Nullable
    public static ReplicationPaths optional(@Nonnull SlingHttpServletRequest request) {
        if (StringUtils.isNotBlank(request.getParameter(PARAM_RELEASEROOT))) {
            return new ReplicationPaths(request);
        }
        return null;
    }

    /**
     * The release root - normally the site that is replicated.
     */
    @Nonnull
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
     * The meaning of this path depends on the context.
     */
    @Nullable
    public String getContentPath() {
        return contentPath;
    }

    @Nonnull
    public MovePostprocessor getMovePostprocessor() {
        if (movePostprocessor == null) {
            movePostprocessor = new MovePostprocessor(getOrigin(), getTargetPath());
        }
        return getMovePostprocessor();
    }

    /**
     * Translates a path on the source side to the target side.
     *
     * @throws IllegalArgumentException if the path is not within {@link #getSourcePath()} / {@link #getReleaseRoot()}
     */
    @Nonnull
    public String translate(@Nonnull String path) {
        String res = Objects.requireNonNull(path);
        String origin = getOrigin();
        if (!SlingResourceUtil.isSameOrDescendant(origin, path)) {
            throw new IllegalArgumentException("Path is outside of source range: " + path);
        }
        if (getTargetPath() != null) {
            String relpath = SlingResourceUtil.relativePath(origin, path);
            res = SlingResourceUtil.appendPaths(targetPath, relpath);
        }
        return res;
    }

    /**
     * The origin: {@link #getSourcePath()} if not null, else {@link #getReleaseRoot()}.
     */
    public String getOrigin() {
        return getSourcePath() != null ? getSourcePath() : getReleaseRoot();
    }

    /**
     * The destination: {@link #getTargetPath()} if not null, else the {@link #getOrigin()}.
     */
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
     * {@link #translate(String)}s a collection of paths.
     */
    @Nonnull
    public List<String> translate(Collection<String> paths) {
        return paths != null ? paths.stream().map(this::translate).collect(Collectors.toList()) : Collections.emptyList();
    }

    /**
     * {@link #translate(String)}s an array of paths.
     */
    @Nonnull
    public String[] translate(String[] paths) {
        return paths != null ? translate(Arrays.asList(paths)).toArray(new String[0]) : new String[0];
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

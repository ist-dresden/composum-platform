package com.composum.sling.platform.staging.replication.postprocess;


import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Postprocessor that adapts the properties of a replicated versionable as if the release is replicated in another
 * location - e.g. /content/some/site goes to /public/some/site. We change String properties that start with the
 * original site's path to the moved location, and if it doesn't start with / we check whether it is a richtext that
 * contains anchors with references to /content/some/site and exchange those to /public/some/site, too.
 */
public class MovePostprocessor {

    private static final Logger LOG = LoggerFactory.getLogger(MovePostprocessor.class);

    protected final String srcPath;
    protected final String targetPath;

    public MovePostprocessor(@Nullable String srcPath, @Nullable String targetPath) {
        if (StringUtils.isNoneBlank(srcPath, targetPath)) {
            this.srcPath = srcPath;
            this.targetPath = targetPath;
        } else {
            this.srcPath = null;
            this.targetPath = null;
        }
    }

    /**
     * Scans the resource and its children for properties that contain the site's path and replaces them.
     */
    public void postprocess(@NotNull Resource resource) {
        if (isNotBlank(srcPath) && isNotBlank(targetPath) && !StringUtils.equals(srcPath, targetPath)) {
            new MovePropertyReplacer(srcPath, targetPath).processResource(resource);
        }
    }

    protected static class MovePropertyReplacer {

        protected final String src;
        protected final String srcDir;
        protected final String dst;
        protected final String dstDir;
        protected final Pattern linkPattern;

        public MovePropertyReplacer(String srcPath, String targetPath) {
            this.src = StringUtils.removeEnd(srcPath, "/");
            this.srcDir = src + "/";
            this.dst = StringUtils.removeEnd(targetPath, "/");
            this.dstDir = dst + "/";
            this.linkPattern = Pattern.compile(
                    "(?<beforepath>(<|&lt;)(a|img)[^>]*\\s(href|src)=(?<quot>['\"]|&quot;))" +
                            Pattern.quote(src) +
                            "(?<relpath>/((?!\\k<quot>).)+)?" +
                            "\\k<quot>"
            );

        }

        protected void processResource(Resource resource) {
            processProperties(resource);
            for (Resource child : resource.getChildren()) {
                processResource(child);
            }
        }

        protected void processProperties(Resource resource) {
            ModifiableValueMap vm = resource.adaptTo(ModifiableValueMap.class);
            for (Map.Entry<String, Object> entry : vm.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String value = (String) entry.getValue();
                    String newValue = null;
                    if (value.equals(src)) {
                        newValue = dst;
                    } else if (value.startsWith(srcDir)) {
                        newValue = dstDir + StringUtils.removeStart(value, srcDir);
                    } else if (!value.startsWith("/")) { // if it starts with / we assume it's an (unrelated) path
                        newValue = transformTextProperty(value);
                    }
                    if (newValue != null) {
                        vm.put(entry.getKey(), newValue);
                    }
                    if (LOG.isInfoEnabled()) { // warning in case the pattern is somehow broken
                        if (((String) entry.getValue()).contains(src)) {
                            LOG.info("Strangely this still contains the move source: {} contains {}",
                                    resource.getPath() + "/" + entry.getValue(), src);
                        }
                    }
                }
            }
        }

        /** transforms embedded references of a rich text from 'content' to the replication path */
        protected String transformTextProperty(String value) {
            StringBuffer result = new StringBuffer();
            Matcher matcher = linkPattern.matcher(value);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String path = StringUtils.defaultString(matcher.group("relpath"));
                String replacedMatch = matcher.group("beforepath") + dst + path + matcher.group("quot");
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacedMatch));
            }
            if (found) {
                matcher.appendTail(result);
                return result.toString();
            } else {
                return null;
            }
        }

    }

}

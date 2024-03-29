package com.composum.sling.platform.staging.replication.json;

import com.composum.platform.commons.json.AbstractJsonTypeAdapterFactory;
import com.composum.platform.commons.json.JsonArrayAsIterable;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.google.gson.Gson;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;

/**
 * Serves as attribute in JSON-serializable classes which can compare versionables in content trees based on the
 * {@link com.composum.sling.platform.staging.StagingConstants#PROP_REPLICATED_VERSION} property.
 * When used with serialization, it prints out the {@link VersionableInfo} for versionables below a given set of
 * resources. When used with deserialization, it compares what it receives with the actual content, keeping
 * only the differences to reduce memory consumption on incremental updates.
 * Since the serialization / deserialization needs parameters, you need register one of
 * {@link VersionableTreeSerializer} / {@link VersionableTreeDeserializer} directly with
 * {@link com.google.gson.GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory)}.
 * <p>
 * Tradeoff: We use the same class for these dual purposes since we'd like to serialize and deserialize the same
 * class when transmitting a request / response. That has the disadvantage of some attributes being useless, depending
 * on the purpose.
 * </p>
 */
public class VersionableTree {

    private static final Logger LOG = LoggerFactory.getLogger(VersionableTree.class);

    protected transient Collection<Resource> searchTreeRoots;

    protected transient List<VersionableInfo> deleted;

    protected transient List<VersionableInfo> changed;

    /**
     * Sets the resources below which we look for versionables.
     */
    public void setSearchtreeRoots(Collection<Resource> resourcesToWrite) {
        this.searchTreeRoots = resourcesToWrite;
    }

    /**
     * A list of VersionableInfo for which we weren't able to find the resource - deleted or moved away.
     */
    @NotNull
    public List<VersionableInfo> getDeleted() {
        if (deleted == null) {
            throw new IllegalStateException("Object was not deserialized.");
        }
        return deleted;
    }

    /**
     * A list of paths for which we weren't able to find the resource - deleted or moved away.
     */
    @NotNull
    public List<String> getDeletedPaths() {
        return getDeleted().stream()
                .map(VersionableInfo::getPath)
                .collect(Collectors.toList());
    }

    /**
     * A list of VersionableInfo for which the resource was changed - it is there but the
     * {@link com.composum.sling.platform.staging.StagingConstants#PROP_REPLICATED_VERSION} is different.
     */
    @NotNull
    public List<VersionableInfo> getChanged() {
        if (changed == null) {
            throw new IllegalStateException("Object was not deserialized.");
        }
        return changed;
    }

    /**
     * A list of paths for which the resource was changed - it is there but the
     * {@link com.composum.sling.platform.staging.StagingConstants#PROP_REPLICATED_VERSION} is different.
     */
    @NotNull
    public List<String> getChangedPaths() {
        return getChanged().stream()
                .map(VersionableInfo::getPath)
                .collect(Collectors.toList());
    }

    /**
     * Compares the {versionableInfoStream} to the resource tree of {resolver} and updates {@link #getDeleted()} and {@link #getChanged()} accordingly.
     */
    public void process(Stream<VersionableInfo> versionableInfoStream, String checkSubpath, Function<String, String> pathMapping, ResourceResolver resolver) {
        if (deleted == null) {
            deleted = new ArrayList<>();
        }
        if (changed == null) {
            changed = new ArrayList<>();
        }

        versionableInfoStream.forEach((info) -> process(info, checkSubpath, pathMapping, resolver));
    }

    /**
     * Compares {info} to the resource tree of {resolver} and updates {@link #getDeleted()} and {@link #getChanged()} accordingly.
     */
    public void process(VersionableInfo info, String checkSubpath, Function<String, String> pathMapping, ResourceResolver resolver) {
        if (deleted == null) {
            deleted = new ArrayList<>();
        }
        if (changed == null) {
            changed = new ArrayList<>();
        }

        if (checkSubpath == null || SlingResourceUtil.isSameOrDescendant(checkSubpath, info.getPath())) {
            String path = pathMapping != null ? pathMapping.apply(info.getPath()) : info.getPath();
            Resource resource = path != null ? resolver.getResource(path) : null;
            if (resource == null) {
                deleted.add(info);
            } else {
                VersionableInfo currentInfo = VersionableInfo.of(resource, null);
                if (currentInfo == null || !currentInfo.getVersion().equals(info.getVersion())) {
                    changed.add(info);
                }
            }
        } else {
            throw new IllegalArgumentException("Not subpath of " + checkSubpath + " : " + info);
        }
    }

    /**
     * Collects the {@link VersionableInfo#of(Resource, Function)} of the search tree roots set with {@link #setSearchtreeRoots(Collection)}.
     */
    @NotNull
    public Stream<VersionableInfo> versionableInfos(@Nullable Function<String, String> pathMapping) {
        Stream<VersionableInfo> versionableInfoStream = searchTreeRoots.stream().filter(Objects::nonNull)
                .flatMap((root) -> SlingResourceUtil.descendantsStream(root, VersionableTree::isVersionableLeaf))
                .map((r) -> VersionableInfo.of(r, pathMapping))
                .filter(Objects::nonNull);
        return versionableInfoStream;
    }

    protected static boolean isVersionableLeaf(Resource resource) {
        if (ResourceUtil.isNodeType(resource, ResourceUtil.TYPE_VERSIONABLE)) {
            if (resource.getValueMap().get(PROP_REPLICATED_VERSION) == null) {
                LOG.warn("Something's wrong here: {} has no {}", resource.getPath(), PROP_REPLICATED_VERSION);
            }
            return true;
        } else if (ResourceUtil.CONTENT_NODE.equals(resource.getName())) {
            LOG.warn("Something's wrong here: {} is not {}", resource.getPath(), ResourceUtil.TYPE_VERSIONABLE);
            return true; // that's not versionable, but we have to return true to avoid descending into it
            // it'll be filtered out later.
        }
        return false;
    }

    public static class VersionableTreeSerializer extends AbstractJsonTypeAdapterFactory<VersionableTree> {

        @Nullable
        private final Function<String, String> pathMapping;

        public VersionableTreeSerializer(@Nullable Function<String, String> pathMapping) {
            super(TypeToken.get(VersionableTree.class));
            this.pathMapping = pathMapping;
        }

        @Override
        protected <TR> void write(@NotNull JsonWriter out, @NotNull VersionableTree value, @NotNull Gson gson, @NotNull TypeToken<TR> requestedType) throws IOException {
            out.beginArray();
            value.versionableInfos(pathMapping)
                    .forEach((info) -> gson.toJson(info, VersionableInfo.class, out));
            out.endArray();
        }

    }

    public static class VersionableTreeDeserializer extends AbstractJsonTypeAdapterFactory<VersionableTree> {
        @Nullable
        protected final Function<String, String> pathMapping;
        @NotNull
        protected final ResourceResolver resolver;
        @Nullable
        protected final String checkSubpath;

        /**
         * Configures the deserialization process to compare with resources relative to {relativeTo} (might be null
         * or / if you want to use the paths directly).
         *
         * @param checkSubpath Safety measure: if that's set, the serializer throws up if the given paths are not at
         *                     this path or a subpath.
         * @param pathMapping  if given, the path is passed through this mapping
         */
        public VersionableTreeDeserializer(@Nullable Function<String, String> pathMapping, @NotNull ResourceResolver resolver,
                                           @Nullable String checkSubpath) {
            super(TypeToken.get(VersionableTree.class));
            this.pathMapping = pathMapping;
            this.resolver = resolver;
            this.checkSubpath = checkSubpath;
        }

        @Nullable
        @Override
        protected <TR extends VersionableTree> VersionableTree read(@NotNull JsonReader in, @NotNull Gson gson,
                                                                    @NotNull TypeToken<TR> requestedType) throws IOException {
            VersionableTree result = makeInstance(requestedType);
            try (
                    JsonArrayAsIterable<VersionableInfo> iterable =
                            new JsonArrayAsIterable<>(in, VersionableInfo.class, gson, null)
            ) {
                result.process(iterable.stream(), checkSubpath, pathMapping, resolver);
            }
            return result;
        }

    }

}

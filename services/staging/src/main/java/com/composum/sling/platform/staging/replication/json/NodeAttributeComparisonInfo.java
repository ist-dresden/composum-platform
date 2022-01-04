package com.composum.sling.platform.staging.replication.json;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.sling.core.filter.StringFilter;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static javax.jcr.PropertyType.BINARY;
import static javax.jcr.PropertyType.BOOLEAN;
import static javax.jcr.PropertyType.DATE;
import static javax.jcr.PropertyType.DECIMAL;
import static javax.jcr.PropertyType.DOUBLE;
import static javax.jcr.PropertyType.LONG;
import static javax.jcr.PropertyType.NAME;
import static javax.jcr.PropertyType.PATH;
import static javax.jcr.PropertyType.REFERENCE;
import static javax.jcr.PropertyType.STRING;
import static javax.jcr.PropertyType.URI;
import static javax.jcr.PropertyType.WEAKREFERENCE;

/**
 * JSON-(de-)serializable description of a nodes attributes, meant for the comparison of the attributes of parent
 * nodes (not for transmitting the attributes - it's not necessary to write attributes back from this representation).
 * We do not support binary attributes - these are meant to be kept in versionables.
 */
@SuppressWarnings("UnstableApiUsage")
public class NodeAttributeComparisonInfo {

    private static final Logger LOG = LoggerFactory.getLogger(NodeAttributeComparisonInfo.class);

    /**
     * The maximum string length that is used directly without hashing it.
     */
    private static final int MAXSTRINGLEN = 64;

    /**
     * Caches for each nodetype (primary and mixin) which properties are protected. We cache this for one resolver
     * only, since that automatically clears the cache without needing to resort to timing etc.
     */
    protected static final Map<ResourceResolver, Map<String, List<String>>> cacheProtectedProperties =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Attributes that legitimately can be different on the remote side / don't matter for the purpose of our
     * comparison. (The change number is compared elsewhere and would just confuse people.)
     */
    protected static final StringFilter IGNORED_ATTRIBUTES =
            new StringFilter.WhiteList(StagingConstants.PROP_CHANGE_NUMBER, StagingConstants.PROP_LAST_REPLICATION_DATE);

    /**
     * Attributes to be included even if they are protected.
     */
    protected static final StringFilter IMPORTANT_ATTRIBUTES =
            new StringFilter.WhiteList(ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.PROP_MIXINTYPES);

    /**
     * The absolute path to the node.
     */
    public String path;

    /**
     * Maps the property names of not protected properties to their String value, or hashes of their value if it's a
     * long string or a data structure. Protected properties are not mentioned, since they cannot be changed, anyway.
     */
    public Map<String, String> propertyHashes;

    /**
     * Only for GSON - use {@link #of(Resource, String)}.
     */
    @Deprecated
    public NodeAttributeComparisonInfo() {
        // empty
    }

    /**
     * Creates the information about one node. This uses JCR since that's the easiest way to exclude protected
     * attributes without having to enumerate all protected attribute names.
     *
     * @param resource    the resource for which we have to compute the attribute info
     * @param pathMapping if given, we pass the path through this mapping
     * @throws IllegalArgumentException if the path of the resource does not start with the given pathOffset
     */
    @NotNull
    public static NodeAttributeComparisonInfo of(@NotNull Resource resource, @Nullable Function<String, String> pathMapping) {
        try {
            HashFunction hash = Hashing.sipHash24();
            NodeAttributeComparisonInfo result = new NodeAttributeComparisonInfo();
            Set<String> protectedProperties = protectedProperties(resource);
            result.path = pathMapping != null ? pathMapping.apply(resource.getPath()) : resource.getPath();
            result.propertyHashes = new TreeMap<>();
            Node node = requireNonNull(resource.adaptTo(Node.class));
            for (PropertyIterator it = node.getProperties(); it.hasNext(); ) {
                Property prop = it.nextProperty();
                String name = prop.getName();
                if (IMPORTANT_ATTRIBUTES.accept(name) ||
                        (!IGNORED_ATTRIBUTES.accept(name) && !protectedProperties.contains(name))) {
                    result.propertyHashes.put(name, stringRep(prop, hash));
                }
            }
            return result;
        } catch (RepositoryException | IOException e) {
            LOG.error("For {}", SlingResourceUtil.getPath(resource), e);
            throw new SlingException("Strange trouble reading attributes of " + SlingResourceUtil.getPath(resource), e);
        } catch (RuntimeException e) {
            LOG.error("Strange trouble reading attributes of {}",
                    SlingResourceUtil.getPath(resource), e);
            throw e;
        }
    }

    /**
     * Returns a list of protected properties.
     * <p>
     * We need to check this by hand, since the staging resource manager does not support
     * {@link Property#getDefinition()}, and is would be a rather large effort to implement this.
     */
    protected static Set<String> protectedProperties(Resource resource) throws RepositoryException {
        Set<String> protectedProperties = new HashSet<>();
        Map<String, List<String>> ourcache = cacheProtectedProperties.computeIfAbsent(resource.getResourceResolver(), ignored ->
                Collections.synchronizedMap(new HashMap<>())
        );
        ValueMap vm = resource.getValueMap();
        List<String> nodeTypes = ListUtils.union(
                Collections.singletonList(vm.get(ResourceUtil.PROP_PRIMARY_TYPE, String.class)),
                Arrays.asList(vm.get(ResourceUtil.PROP_MIXINTYPES, new String[0]))
        );
        for (String type : nodeTypes) {
            List<String> props = ourcache.get(type);
            if (props == null) {
                props = new ArrayList<>();
                NodeTypeManager nodeTypeManager = requireNonNull(resource.getResourceResolver().adaptTo(Session.class))
                        .getWorkspace().getNodeTypeManager();
                NodeType nodeType = nodeTypeManager.getNodeType(type);
                for (PropertyDefinition def : nodeType.getPropertyDefinitions()) {
                    if (def.isProtected()) {
                        props.add(def.getName());
                    }
                }
                ourcache.putIfAbsent(type, props);
            }
            protectedProperties.addAll(props);
        }
        return protectedProperties;
    }

    /**
     * Creates a String representation of the property that is limited in size (long / complicated properties are
     * hashed). The representation is meant to be of small size, but make it extremely unlikely that different values
     * have the same representation. It is prefixed with the property type.
     */
    protected static String stringRep(Property prop, HashFunction hash) throws RepositoryException, IOException {
        StringBuilder buf = new StringBuilder();
        buf.append(typeCode(prop.getType())).append(":");
        if (prop.isMultiple()) {
            Value[] values = prop.getValues();
            if (valueOrderingIsUnimportant(prop.getName())) {
                Arrays.sort(values, Comparator.comparing(ExceptionUtil.sneakExceptions(Value::getString)));
            }
            Hasher hasher = hash.newHasher();
            for (Value value : values) {
                String valueRep = valueRep(value, prop.getType(), hash);
                hasher.putUnencodedChars(valueRep);
            }
            buf.append(encode(hasher.hash()));
        } else {
            String valueRep = valueRep(prop.getValue(), prop.getType(), hash);
            if (valueRep.length() > MAXSTRINGLEN) {
                valueRep = encode(hash.hashUnencodedChars(valueRep));
            }
            buf.append(valueRep);
        }
        return buf.toString();
    }

    /**
     * A string representation of the property - generated depending on the type. Given the type, it should be
     * unique to the value.
     */
    protected static String valueRep(@NotNull Value value, int type, HashFunction hash) throws RepositoryException, IOException {
        switch (type) {
            case DATE:
                Calendar date = value.getDate();
                return date != null ? String.valueOf(date.getTimeInMillis()) : "";
            case BINARY:
                Binary binary = value.getBinary();
                Hasher hasher = hash.newHasher();
                byte[] buf = new byte[8192];
                try (InputStream stream = binary != null ? binary.getStream() : null) {
                    if (stream != null) {
                        int len;
                        while ((len = stream.read(buf)) > 0) {
                            hasher.putBytes(buf, 0, len);
                        }
                    }
                } finally {
                    if (binary != null) {
                        binary.dispose();
                    }
                }
                return encode(hasher.hash());
            case DOUBLE:
                return String.valueOf(value.getDouble());
            case DECIMAL:
                return String.valueOf(value.getDecimal());
            case LONG:
                return String.valueOf(value.getLong());
            case BOOLEAN:
                return String.valueOf(value.getBoolean());
            case STRING:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
            case URI:
                return value.getString();
            default: // impossible
                throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    /**
     * We unify some types since they can differ in the two repositories even after correct transmission.
     */
    protected static String typeCode(int type) {
        switch (type) {
            case DATE:
                return "C"; // calendar
            case BINARY:
                return "B";
            case LONG:
            case DOUBLE:
                return "n"; // number
            case DECIMAL:
                return "D";
            case BOOLEAN:
                return "b";
            case STRING:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
            case URI:
                return "S";
            default: // impossible
                throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    protected static boolean valueOrderingIsUnimportant(String propertyName) {
        return JcrConstants.JCR_MIXINTYPES.equals(propertyName);
    }

    protected static String encode(HashCode hash) {
        return hash.toString();
    }

    /**
     * Compares path and attributes.
     */
    @SuppressWarnings("ObjectEquality")
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NodeAttributeComparisonInfo that = (NodeAttributeComparisonInfo) o;
        return Objects.equals(path, that.path) &&
                Objects.equals(propertyHashes, that.propertyHashes);
    }

    @SuppressWarnings("ObjectInstantiationInEqualsHashCode")
    @Override
    public int hashCode() {
        return Objects.hash(path, propertyHashes);
    }

    @Override
    public String toString() {
        return "NodeAttributeComparisonInfo{" + "path='" + path + '\'' +
                ", propertyHashes=" + propertyHashes +
                '}';
    }

    /**
     * Human readable description of the difference between two attributeInfo - for logging / debugging purposes
     * only.
     */
    public String difference(@NotNull NodeAttributeComparisonInfo other) {
        StringBuilder buf = new StringBuilder();
        if (!StringUtils.equals(path, other.path)) {
            buf.append("Paths different. ");
        }
        for (String name : SetUtils.union(propertyHashes.keySet(), other.propertyHashes.keySet())) {
            String val = propertyHashes.get(name);
            String val2 = other.propertyHashes.get(name);
            if (!StringUtils.equals(val, val2)) {
                buf.append(name).append("=").append(val).append("|").append(val2).append(" ");
            }
        }
        return buf.toString();
    }
}

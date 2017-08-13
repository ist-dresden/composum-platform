package com.composum.sling.platform.staging;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.Iterator;

import static com.composum.sling.platform.staging.StagingUtils.VERSIONS_ROOT;
import static com.composum.sling.platform.staging.StagingUtils.isInVersionStorage;
import static com.composum.sling.platform.staging.StagingUtils.isPropertyResource;
import static com.composum.sling.platform.staging.StagingUtils.isRoot;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENUUID;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.sling.jcr.resource.api.JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY;

/** Provides a view of a frozen node as if it was a normal resource. */
public class StagingResource implements Resource {

    private static final Logger LOGGER = LoggerFactory.getLogger(StagingResource.class);

    /**
     * The ResourceResolver used to create this Resource Object.
     */
    @Nonnull
    private final StagingResourceResolver resourceResolver;

    /**
     * The Resource inside the version storage.
     */
    @Nonnull
    private final Resource frozenResource;

    /**
     * are we in the versioned tree or not
     */
    private final boolean versioned;

    /**
     * Resource path outside the version storage, even if it does not exist anymore.
     */
    @Nonnull
    private final String originalResourcePath;

    /**
     * The name of the resource as it where not inside the version storage.
     * This means 'jcr:frozenNode' is replaced with the original name.
     */
    @Nonnull
    private final String originalName;

    /**
     * The original type of the resource. If it is a resource inside the version storage, it is the content of is
     * the value from the 'jcr:frozenPrimaryType', if no 'sling:resourceType' is defined.
     */
    @Nonnull
    private final String originalResourceType;

    /**
     * If this resource is resolved from a HTTP-Request, the request is sored here
     */
    @CheckForNull
    private final SlingHttpServletRequest request;

    /**
     * Constructs a new StagingResource.
     *
     * @param frozenResource          the resource wrapped by this instance. If it' a StagingResource or a
     *                                nonexisting resource, it is returned unchanged.
     * @param stagingResourceResolver the ResourceResolver that created this StagingResource
     */
    public static Resource wrap(@Nonnull Resource frozenResource,
                                @Nonnull StagingResourceResolver stagingResourceResolver) {
        if (ResourceUtil.isNonExistingResource(frozenResource)) {
            return frozenResource;
        }
        return frozenResource instanceof StagingResource
                ? frozenResource
                : new StagingResource(frozenResource, stagingResourceResolver);
    }

    /**
     * Constructs a new StagingResource.
     *
     * @param frozenResource          the resource wrapped by this instance. If it' a StagingResource or a
     *                                nonexisting resource, it is returned unchanged.
     * @param stagingResourceResolver the ResourceResolver that created this StagingResource
     */
    public static Resource wrap(@Nullable SlingHttpServletRequest request, @Nonnull Resource frozenResource,
                                @Nonnull StagingResourceResolver stagingResourceResolver) {
        if (ResourceUtil.isNonExistingResource(frozenResource)) {
            return frozenResource;
        }
        return frozenResource instanceof StagingResource
                ? frozenResource
                : new StagingResource(request, frozenResource, stagingResourceResolver);
    }

    /**
     * Constructs a new StagingResource.
     *
     * @param frozenResource          the resource wrapped by this instance. This must not be a StagingResource.
     * @param stagingResourceResolver the ResourceResolver that created this StagingResource
     */
    private StagingResource(@Nonnull Resource frozenResource, @Nonnull StagingResourceResolver stagingResourceResolver) {
        this(null, frozenResource, stagingResourceResolver);
    }

    /**
     * Constructs a new StagingResource.
     *
     * @param request                 the request initiating the resolving of the resource
     * @param frozenResource          the resource wrapped by this instance. This must not be a StagingResource.
     * @param stagingResourceResolver the ResourceResolver that created this StagingResource
     */
    private StagingResource(@Nullable SlingHttpServletRequest request, @Nonnull Resource frozenResource, @Nonnull StagingResourceResolver stagingResourceResolver) {
        Validate.isTrue(!(frozenResource instanceof StagingResource), "resource to wrap is already a StagingResource:" +
                " %s", frozenResource); // impossible.
        this.versioned = frozenResource.getPath().startsWith(VERSIONS_ROOT);
        this.frozenResource = frozenResource;
        this.resourceResolver = stagingResourceResolver;
        this.originalResourcePath = calculatePath();
        this.originalName = calculateName();
        this.originalResourceType = calculateResourceType();
        this.request = request;
    }

    /**
     * Gets the ResourceType of the resource. If the resource is a property the name is build up of the type of the
     * parent and the name of the property. For a node resource the sling resource type is used if existing and the jcr
     * primary type if not.
     *
     * @return the ResourceTyp
     */
    @Nonnull
    private String calculateResourceType() {
        if (isPropertyResource(frozenResource)) {
            final String parentResourceType = StagingResource.wrap(frozenResource.getParent(), resourceResolver).getResourceType();
            return parentResourceType + "/" + frozenResource.getName();
        } else {
            final String slingResourceType = frozenResource.getValueMap().get(SLING_RESOURCE_TYPE_PROPERTY, String.class);
            if (StringUtils.isBlank(slingResourceType)) {
                if (isInVersionStorage(frozenResource)) {
                    // if in version storage, the primary type is stored in jcr:frozenPrimaryType
                    return frozenResource.getValueMap().get(JCR_FROZENPRIMARYTYPE, String.class);
                } else {
                    // else use jcr:primaryType
                    return frozenResource.getValueMap().get(JCR_PRIMARYTYPE, String.class);
                }
            }
            return slingResourceType;
        }
    }

    /**
     * Gets the of the resource. If the resource is the 'jcr:frozenNode' itself, the UID is used to find the node in the
     * repository ant get its name. If this fails, the 'default' property of the version storage rout for this resource
     * is used to find the name.
     * Otherwise the name is the name of the node itself.
     *
     * @return the name of the resource
     */
    @Nonnull
    private String calculateName() {
        try {
            final ResourceResolver delegateeResourceResolver = resourceResolver.getDelegateeResourceResolver();
            final Session session = delegateeResourceResolver.adaptTo(Session.class);
            Resource frozenResourceToUse = frozenResource;
            if (!frozenResourceToUse.getName().equals(JCR_FROZENNODE)) {
                return frozenResourceToUse.getName();
            } else {
                final ValueMap frozenResourceValueMap = frozenResourceToUse.getValueMap();
                try {
                    final Node originalNode = session.getNodeByIdentifier(frozenResourceValueMap.get(JCR_FROZENUUID, String.class));
                    final Resource topResource = delegateeResourceResolver.resolve(originalNode.getPath());
                    return topResource.getName();
                } catch (ItemNotFoundException infE) {
                    final String path = frozenResourceToUse.getParent().getParent().getValueMap().get("default", String.class);
                    return ResourceUtil.getName(path);
                }
            }
        } catch (RepositoryException e) {
            LOGGER.error("could not construct name for StagingResource", e);
            throw new StagingException("could not construct name for StagingResource", e);
        }
    }

    /**
     * Gets a path for the resource. This path may not exist in the repository if the resource was deleted and only
     * exists in the version storage. The path points to a location outside the version storage.
     *
     * @return a path
     */
    @Nonnull
    private String calculatePath() {
        try {
            final ResourceResolver delegateeResourceResolver = resourceResolver.getDelegateeResourceResolver();
            final Session session = delegateeResourceResolver.adaptTo(Session.class);
            Resource frozenResourceToUse = frozenResource;
            String intermediatePath = "";
            while (!frozenResourceToUse.getName().equals(JCR_FROZENNODE)) {
                if (intermediatePath.length() == 0) {
                    intermediatePath = frozenResourceToUse.getName();
                } else {
                    intermediatePath = frozenResourceToUse.getName() + "/" + intermediatePath;
                }
                frozenResourceToUse = frozenResourceToUse.getParent();
                if (frozenResourceToUse == null || isRoot(frozenResourceToUse)) {
                    // we are going to wrap a JcrResource.
                    // so its path is the one from the original resource
                    return frozenResource.getPath();
                }
            }
            final ValueMap frozenResourceValueMap = frozenResourceToUse.getValueMap();
            //handle ItemNotFoundException - can this really happen? - yes, if node is deleted and only exists in history.
            String topResourcePath;
            try {
                final Node originalNode = session.getNodeByIdentifier(frozenResourceValueMap.get(JCR_FROZENUUID, String.class));
                final Resource topResource = delegateeResourceResolver.resolve(originalNode.getPath());
                topResourcePath = topResource.getPath();
            } catch (ItemNotFoundException infE) {
                if (StringUtils.isBlank(intermediatePath)) {
                    //we have a versionable resource existing only in history - not a subnode of a versionable but the node itself!
                    // so we have to go up two levels in version history and read the property 'default'
                    topResourcePath = frozenResource.getParent().getParent().getValueMap().get("default", String.class);
                } else {
                    topResourcePath = frozenResourceToUse.getParent().getParent().getValueMap().get("default", String.class);
                }
            }
            return StringUtils.isBlank(intermediatePath) ? topResourcePath : topResourcePath + "/" + intermediatePath;
        } catch (RepositoryException e) {
            LOGGER.error("could not reconstruct path of StagingResource", e);
            throw new StagingException("could not reconstruct path of StagingResource", e);
        }
    }

    /**
     * Returns the resource inside the version storage. Not intended for use from outside the staging implementation.
     *
     * @return the resource from the version storage
     */
    @Nonnull
    public Resource getFrozenResource() {
        return frozenResource;
    }

    @Override
    @Nonnull
    public String getPath() {
        final String path = originalResourcePath;
        LOGGER.debug("getPath(): {}", path);
        return path;
    }

    @Override
    @Nonnull
    public String getName() {
        final String name = originalName;
        LOGGER.debug("getName(): {}", name);
        return name;
    }

    @Override
    @CheckForNull
    public Resource getParent() {
        LOGGER.debug("getParent()");
        if (isRoot(frozenResource)) {
            return null;
        }
        String parentPath;
        if (originalResourcePath.endsWith("/")) {
            final String s = originalResourcePath.substring(0, originalResourcePath.length() - 1);
            parentPath = s.substring(0, s.lastIndexOf('/'));
        } else {
            parentPath = originalResourcePath.substring(0, originalResourcePath.lastIndexOf('/'));
        }
        return resourceResolver.resolve(parentPath);
    }

    @Override
    @Nonnull
    public Iterator<Resource> listChildren() {
        LOGGER.debug("listChildren()");
        return resourceResolver.listChildren(this);
    }

    @Override
    @Nonnull
    public Iterable<Resource> getChildren() {
        LOGGER.debug("getChildren()");
        // if this is not a node of version storage, this will result in all current children, not the children of the released version
        // this can happen, if this only wraps a JcrResource and so frozenNode is only a JcrResource
        // but we will handle it inside ResourceResolver/ChildrenIterator
        final Iterator<Resource> iterator = resourceResolver.listChildren(this);
        return new StagingResourceChildrenIterable(iterator);
    }

    @Override
    @CheckForNull
    public Resource getChild(@Nonnull String relPath) {
        LOGGER.debug("getChild({})", relPath);
        final Resource child = versioned
                ? frozenResource.getChild(relPath)
                : resourceResolver.getResource(frozenResource, relPath);
        return child == null ? null : StagingResource.wrap(child, resourceResolver);
    }

    @Override
    @Nonnull
    public String getResourceType() {
        LOGGER.debug("getResourceType(): {}", originalResourceType);
        return originalResourceType;
    }

    @Override
    @CheckForNull
    public String getResourceSuperType() {
        LOGGER.debug("getResourceSuperType()");
        return frozenResource.getResourceSuperType();
    }

    @Override
    @CheckReturnValue
    public boolean hasChildren() {
        final boolean b = versioned
                ? frozenResource.hasChildren()
                : resourceResolver.hasChildren(frozenResource);
        LOGGER.debug("hasChildren(): {}", b);
        return b;
    }

    @Override
    @CheckReturnValue
    public boolean isResourceType(String resourceType) {
        final boolean b = !StringUtils.isBlank(frozenResource.getResourceType()) && frozenResource.getResourceType().equals(resourceType);
        LOGGER.debug("isResourceType({}): {}", resourceType, b);
        return b;
    }

    @Override
    @Nonnull
    public ResourceMetadata getResourceMetadata() {
        LOGGER.debug("getResourceMataData()");
        final ResourceMetadata resourceMetadata;
        if (request != null) {
            resourceMetadata = new StagingResourceMetadata(
                    frozenResource.getResourceMetadata(),
                    //TODO requestPathInfo.resourcePath vs. resourcePath
                    request.getRequestPathInfo().getResourcePath(),
                    request.getRequestPathInfo().getExtension());
        } else {
            resourceMetadata = new StagingResourceMetadata(
                    frozenResource.getResourceMetadata(),
                    originalResourcePath,
                    null);
        }
        resourceMetadata.lock();
        return resourceMetadata;
    }

    @Override
    @Nonnull
    public ResourceResolver getResourceResolver() {
        LOGGER.debug("getResourceResolver()");
        return resourceResolver;
    }

    @Override
    @Nonnull
    public ValueMap getValueMap() {
        LOGGER.debug("getValueMap()");
        final ValueMap fvm = frozenResource.getValueMap();
        return new StagingResourceValueMap(fvm);
    }

    @Override
    public <AdapterType> AdapterType adaptTo(@Nonnull Class<AdapterType> type) {
        LOGGER.debug("adaptTo({})", type);
        if (ValueMap.class.isAssignableFrom(type)) {
            final ValueMap fvm = frozenResource.getValueMap();
            return type.cast(new StagingResourceValueMap(fvm));
        }
        return frozenResource.adaptTo(type);
    }

    @Override
    @Nonnull
    public String toString() {
        return getClass().getSimpleName()
                + ", type=" + getResourceType()
                + ", superType=" + getResourceSuperType()
                + ", path=" + getPath()
                + ", frozenType=" + frozenResource.getResourceType()
                + ", frozenSuperType=" + frozenResource.getResourceSuperType()
                + ", frozenPath=" + frozenResource.getPath();
    }

}

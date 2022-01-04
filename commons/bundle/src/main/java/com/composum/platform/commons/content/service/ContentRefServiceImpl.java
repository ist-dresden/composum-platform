package com.composum.platform.commons.content.service;

import com.composum.platform.commons.request.service.InternalRequestService;
import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.Binary;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.util.regex.Pattern;

@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Content Reference Service"
        }
)
public class ContentRefServiceImpl implements ContentRefService {

    private static final Logger LOG = LoggerFactory.getLogger(ContentRefServiceImpl.class);

    private static final Pattern SERVLET_URI = Pattern.compile("/bin/(public|cpm)/.*\\.[^./]+/.*^$");

    @Reference
    protected InternalRequestService internalRequestService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    protected volatile HtmlImageRenderer htmlImageRenderer;

    @Override
    @NotNull
    public String getReferencedContent(@NotNull ResourceResolver resolver, String path) {
        String content = "";
        if (StringUtils.isNotBlank(path) && !SERVLET_URI.matcher(path).matches()) {
            Resource resource = resolver.getResource(path);
            if (resource != null && ResourceUtil.isFile(resource)) {
                Binary binary = ResourceUtil.getBinaryData(resource);
                try {
                    content = IOUtils.toString(binary.getStream(), CODE_ENCODING);
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                } finally {
                    binary.dispose();
                }
            } else {
                LOG.warn("resource not found or not a file '{}'", path);
            }
        }
        return content;
    }

    @Override
    @NotNull
    public String getRenderedContent(@NotNull SlingHttpServletRequest contextRequest, String url, boolean emptyLines) {
        String content = "";
        if (StringUtils.isNotBlank(url)) {
            try {
                InternalRequestService.PathInfo pathInfo =
                        new InternalRequestService.PathInfo(contextRequest, url);
                content = internalRequestService.getString(contextRequest, pathInfo);
                if (!emptyLines) {
                    content = content.replaceAll("(?m)^\\s+$", ""); // remove empty lines
                }
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
            }
        }
        return content;
    }

    @Override
    @Nullable
    public BufferedImage getRenderedImage(@NotNull final SlingHttpServletRequest contextRequest,
                                          @NotNull final String url, int width, @Nullable final Integer height,
                                          @Nullable final Double scale) {
        BufferedImage image = null;
        if (htmlImageRenderer != null) {
            final String content = getRenderedContent(contextRequest, url, false);
            if (StringUtils.isNotBlank(content)) {
                try {
                    image = htmlImageRenderer.htmlToImage(contextRequest, url, new StringReader(content),
                            width, height, scale, Color.WHITE);
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                }
            }
        }
        return image;
    }
}

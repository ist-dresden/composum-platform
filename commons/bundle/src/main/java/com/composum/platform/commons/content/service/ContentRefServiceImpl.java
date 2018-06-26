package com.composum.platform.commons.content.service;

import com.composum.platform.commons.request.service.InternalRequestService;
import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.Binary;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
@Component(
        label = "Composum Platform Content Reference Service",
        description = "retrieves the content of referenced resources"
)
@Service
public class ContentRefServiceImpl implements ContentRefService {

    private static final Logger LOG = LoggerFactory.getLogger(ContentRefServiceImpl.class);

    private static final Pattern SERVLET_URI = Pattern.compile("/bin/(public|cpm)/.*\\.[^./]+/.*^$");

    @Reference
    protected InternalRequestService internalRequestService;

    @Override
    @Nonnull
    public String getReferencedContent(@Nonnull ResourceResolver resolver, String path) {
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
    @Nonnull
    public String getRenderedContent(@Nonnull SlingHttpServletRequest contextRequest, String url, boolean emptyLines) {
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
}

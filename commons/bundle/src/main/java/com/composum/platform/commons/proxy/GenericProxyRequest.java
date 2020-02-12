package com.composum.platform.commons.proxy;

import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * a configurable proxy request service factory usable directly and also as a base for special proxy implementations
 */
@Component(service = ProxyRequestService.class, scope = ServiceScope.PROTOTYPE)
@Designate(ocd = ProxyRequestConfig.class, factory = true)
public class GenericProxyRequest implements ProxyRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(GenericProxyRequest.class);

    public static final Pattern XML_CONTENT_URL = Pattern.compile("^.*/[^/]+\\.(html|xml)(\\?.*)?$");

    protected ProxyRequestConfig config;

    protected Pattern targetPattern;

    @Activate
    @Modified
    protected void activate(final ProxyRequestConfig config) {
        this.config = config;
        if (config.enabled()) {
            targetPattern = Pattern.compile(config.targetPattern());
        }
    }

    @Override
    @Nonnull
    public String getName() {
        return config.name();
    }


    /**
     * Handles the proxy request if appropriate (target pattern matches and access allowed)
     *
     * @param request   the proxy request
     * @param response  the response for the answer
     * @param targetUrl the url of the request which is addressing the target
     * @return 'true' if the request is supported by the service, allowed for the user and handle by the service
     */
    public boolean doProxy(@Nonnull final SlingHttpServletRequest request,
                           @Nonnull final SlingHttpServletResponse response,
                           @Nonnull final String targetUrl)
            throws IOException {
        if (config.enabled()) {
            Matcher matcher = targetPattern.matcher(targetUrl);
            if (matcher.find()) {
                try {
                    String referencePath = config.referencePath();
                    if (StringUtils.isNotBlank(referencePath)) {
                        ResourceResolver resolver = request.getResourceResolver();
                        if (resolver.getResource(referencePath) == null) {
                            return false; // access not allowed - this service can't handle the proxy request
                        }
                    }
                    doRequest(request, response, targetUrl, matcher);
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
                return true; // if this service was the right one signal the proxy handling even if an erros has occured
            }
        }
        return false;
    }

    /**
     * Send the request to the proxies target and sends the reveived answer ans response
     *
     * @param request   the request to the proxy servlet
     * @param response  the response of the rquest to the proxy servlet
     * @param targetRef the URL derived from the request to the proxy servlet
     * @param matcher   the prepared matcher used to determine this proxy service implementation as the right one
     */
    protected void doRequest(@Nonnull final SlingHttpServletRequest request,
                             @Nonnull final SlingHttpServletResponse response,
                             @Nonnull final String targetRef,
                             @Nonnull final Matcher matcher)
            throws Exception {
        String targetUrl = getTargetUrl(request, targetRef, matcher);
        if (StringUtils.isNotBlank(targetUrl)) {
            CloseableHttpClient client = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet(targetUrl);
            LOG.info("proxy request '{}'", httpGet.getRequestLine());
            try (CloseableHttpResponse targetResponse = client.execute(httpGet)) {
                final HttpEntity entity = targetResponse.getEntity();
                if (entity != null) {
                    doResponse(request, response, targetUrl, entity);
                } else {
                    LOG.warn("response is NULL ({})", targetUrl);
                }
            }
        } else {
            LOG.info("no target URL: NOP ({})", targetRef);
        }
    }

    /**
     * Prepare, filter and deliver the content entity reveived from the target.
     *
     * @param request   the request to the proxy servlet
     * @param response  the response object to send the answer
     * @param targetUrl the URL used to request the entity
     * @param entity    the content received from the target
     */
    protected void doResponse(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final String targetUrl,
                              @Nonnull final HttpEntity entity)
            throws IOException {
        try (InputStream inputStream = entity.getContent()) {
            if (isXmlRequest(targetUrl)) {
                SAXTransformerFactory stf = null;
                XMLFilter xmlFilter = null;
                String[] xsltChainPaths = config.XSLT_chain_paths();
                if (xsltChainPaths.length > 0) {
                    // build XML filter for XSLT transformation
                    stf = (SAXTransformerFactory) TransformerFactory.newInstance();
                    xmlFilter = getXsltFilter(stf, request.getResourceResolver(), xsltChainPaths);
                }
                if (xmlFilter != null) {
                    // do XSLT transformation (probably pre-filtered by the reader)...
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("XSLT transformation ({})...", xmlFilter);
                    }
                    try (Reader entityReader = getContentReader(targetUrl, inputStream)) {
                        Transformer transformer = stf.newTransformer();
                        SAXSource transformSource = new SAXSource(xmlFilter, new InputSource(entityReader));
                        transformer.transform(transformSource, new StreamResult(response.getWriter()));
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("pumping HTML/XML content...");
                    }
                    // stream entity response (probably filtered by the reader)
                    try (Reader entityReader = getContentReader(targetUrl, inputStream)) {
                        IOUtils.copy(entityReader, response.getWriter());
                    }
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("pumping non XML content...");
                }
                // stream entity response (probably filtered by the reader)
                try (Reader entityReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                    IOUtils.copy(entityReader, response.getWriter());
                }
            }
        } catch (TransformerException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    /**
     * @return 'true' if XHTML/XML content will be expected requesting the given URL
     */
    protected boolean isXmlRequest(@Nonnull final String targetUrl) {
        return XML_CONTENT_URL.matcher(targetUrl).matches();
    }

    /**
     * the factory method for the reader to prepare and filter the content received from the target
     *
     * @param targetUrl     the URL used to request the entity
     * @param entityContent the received content as stream
     * @return the reader to use to receive the content
     */
    @Nonnull
    protected Reader getContentReader(@Nonnull final String targetUrl,
                                      @Nonnull final InputStream entityContent) {
        String[] toRename = config.tags_to_rename();
        String[] toStrip = config.tags_to_strip();
        String[] toDrop = config.tags_to_drop();
        Reader reader = toRename.length > 0 || toStrip.length > 0 || toDrop.length > 0
                ? new TagFilteringReader(entityContent, toRename, toStrip, toDrop)
                : new InputStreamReader(entityContent, StandardCharsets.UTF_8);
        if (LOG.isDebugEnabled()) {
            LOG.debug("using reader '{}' ({})", reader, targetUrl);
        }
        return reader;
    }

    /**
     * Builds the URL for the target request using the URI built by the ProxyServlet and the matcher of that URI.
     *
     * @param request   the original request received by the ProxyServlet
     * @param targetRef the target URI derived from the original request (suffix + query string)
     * @param matcher   the URI pattern matcher (gives access to the groups declared by the pattern)
     * @return the URL for the HTTP request to the target
     */
    @Nullable
    protected String getTargetUrl(@Nonnull final SlingHttpServletRequest request,
                                  @Nonnull final String targetRef, @Nonnull final Matcher matcher) {
        String targetUrl = config.targetUrl();
        if (StringUtils.isNotBlank(targetUrl)) {
            // if a targetURL is configured use the configured pattern to build the final URL based on the requested
            // URI; the configured targetUrl can contain value placeholders ${0},${1},... to embed groups of the matcher
            Map<String, Object> properties = new HashMap<>();
            properties.put("url", targetRef);
            for (int i = 0; i < matcher.groupCount(); i++) { // add all available groups as properties
                properties.put(Integer.toString(i), matcher.group(i));
            }
            ValueEmbeddingReader reader = new ValueEmbeddingReader(new StringReader(targetUrl), properties);
            try {   // replace the palceholders of the target URL and embed the referenced properties...
                targetUrl = IOUtils.toString(reader);
            } catch (IOException ex) {
                LOG.error(ex.toString());
                targetUrl = null;
            }
        } else {
            targetUrl = targetUrl.startsWith("/") // complete a path and prepend host and port
                    ? (request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + targetRef)
                    : targetRef;
        }
        return targetUrl;
    }

    //
    // XSLT transformation
    //

    /**
     * @return a chain of XML filters initialized with the XSLT resources resolved from the given path list
     */
    @Nullable
    protected XMLFilter getXsltFilter(@Nonnull final SAXTransformerFactory stf,
                                      @Nonnull final ResourceResolver resolver,
                                      @Nonnull final String[] xsltChainPaths) {
        XMLFilter xmlFilter = null;
        try {
            for (String xsltPath : xsltChainPaths) {
                XMLFilter next = getXmlFilter(stf, resolver.getResource(xsltPath));
                if (next != null) {
                    if (xmlFilter == null) {
                        SAXParserFactory spf = SAXParserFactory.newInstance();
                        spf.setNamespaceAware(true);
                        spf.setValidating(false);
                        SAXParser parser = spf.newSAXParser();
                        XMLReader reader = parser.getXMLReader();
                        next.setParent(reader);
                    } else {
                        next.setParent(xmlFilter);
                    }
                    xmlFilter = next;
                }
            }
        } catch (ParserConfigurationException | SAXException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return xmlFilter;
    }

    public static XMLFilter getXmlFilter(@Nonnull final SAXTransformerFactory stf,
                                         @Nullable final Resource xsltResource) {
        XMLFilter filter = null;
        InputStream inputStream = getFileContent(xsltResource);
        if (inputStream != null) {
            try {
                filter = stf.newXMLFilter(new StreamSource(new InputStreamReader(inputStream, StandardCharsets.UTF_8)));
            } catch (TransformerConfigurationException ex) {
                LOG.error(ex.getMessage(), ex);
            }
        }
        return filter;
    }

    @Nullable
    public static InputStream getFileContent(@Nullable Resource resource) {
        InputStream inputStream = null;
        if ((resource = getFileResource(resource)) != null) {
            ValueMap values = resource.getValueMap();
            inputStream = values.get(JcrConstants.JCR_DATA, InputStream.class);
        }
        return inputStream;
    }

    @Nullable
    public static Resource getFileResource(@Nullable final Resource resource) {
        return resource != null && resource.isResourceType(JcrConstants.NT_FILE)
                ? resource.getChild(JcrConstants.JCR_CONTENT) : resource;
    }
}

package com.composum.platform.commons.request.service.impl;

import com.composum.platform.commons.request.AccessMode;
import com.composum.platform.commons.request.service.PlatformRequestLogger;
import com.composum.platform.commons.util.FileRequestLog;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.engine.RequestLog;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("deprecation")
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Platform Request Logging Service"
        },
        immediate = true
)
public class DefaultPlatformRequestLogger implements PlatformRequestLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPlatformRequestLogger.class);

    protected BundleContext bundleContext;

    protected Map<String, RequestLog> modeLogs = new HashMap<>();

    @Nonnull
    protected RequestLog getLog(@Nullable final AccessMode accessMode) throws IOException {
        String modeKey = accessMode != null ? accessMode.name().toLowerCase() : "other";
        RequestLog log = modeLogs.get(modeKey);
        if (log == null) {
            String fileName = getLogFilename(modeKey);
            File file = new File(fileName);
            if (!file.isAbsolute()) {
                final String home = bundleContext.getProperty("sling.home");
                if (home != null) {
                    file = new File(home, fileName);
                }
                file = file.getAbsoluteFile();
            }
            log = new FileRequestLog(file);
            modeLogs.put(modeKey, log);
        }
        return log;
    }

    protected String getLogFilename(String modeKey) {
        return "logs/request-" + modeKey + ".log";
    }

    protected void logMessage(@Nonnull final StringBuilder builder,
                              @Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response) {
        Data data = PlatformRequestLogger.data(request);
        long duration = data.getRequestEndTimeMs() - data.getRequestStartTimeMs();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");
        Resource resource = request.getResource();
        String serverHost = request.getServerName();
        String queryString = request.getQueryString();
        String referer = request.getHeader(HttpHeaders.REFERER);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        builder.append(dateFormat.format(new Date(data.getRequestStartTimeMs())))
                .append(' ')
                .append(response.getStatus())
                .append(' ')
                .append(request.getServerName())
                .append(' ')
                .append(request.getMethod())
                .append(" [")
                .append(resource.getPath())
                .append("] '")
                .append(request.getRequestURI());
        if (StringUtils.isNotBlank(queryString)) {
            builder.append('?').append(queryString);
        }
        builder.append("' (")
                .append(request.getRemoteUser())
                .append(") '")
                .append(referer != null ? referer : "-")
                .append("' {")
                .append(userAgent != null ? userAgent : "?")
                .append('}');
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public boolean canHandle(@Nullable final AccessMode accessMode,
                             @Nonnull final SlingHttpServletRequest request,
                             @Nonnull final SlingHttpServletResponse response) {
        return true;
    }

    @Override
    public void logRequest(@Nullable final AccessMode accessMode,
                           @Nonnull final SlingHttpServletRequest request,
                           @Nonnull final SlingHttpServletResponse response) {
        try {
            StringBuilder builder = new StringBuilder();
            logMessage(builder, request, response);
            getLog(accessMode).write(builder.toString());
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    @Activate
    protected void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Deactivate
    void deactivate() {
        for (RequestLog log : modeLogs.values()) {
            log.close();
        }
        modeLogs.clear();
    }
}

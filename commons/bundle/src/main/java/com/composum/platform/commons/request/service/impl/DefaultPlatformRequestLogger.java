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
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Request Logging Service",
        }
)
@Designate(ocd = DefaultPlatformRequestLogger.Config.class, factory = true)
public class DefaultPlatformRequestLogger implements PlatformRequestLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPlatformRequestLogger.class);

    @ObjectClassDefinition(name = "Composum Platform Request Logging Service",
            description = "Configures a request logger driven by the Composum Platform in the context of the available platform access modes."
    )
    @interface Config {

        @AttributeDefinition(name = "Name",
                description = "the name / identifier of this logger")
        String name() default "default";

        @AttributeDefinition(name = "enabled",
                description = "The on/off switch for the logging service")
        boolean enabled() default false;

        @AttributeDefinition(name = "Logfile Base",
                description = "the path and filename base for this logger")
        String logfile_base() default "logs/request-";

        @AttributeDefinition(name = "Matching URIs",
                description = "A regex pattern set of matching URIs to trigger this logger.")
        String[] pattern_uri_matching() default {
                ".*"
        };

        @AttributeDefinition(name = "Ignored URIs",
                description = "A regex pattern set of URIs to ignore.")
        String[] pattern_uri_ignore() default {
                "/bin/cpm/.*",
                "/libs/composum/.*"
        };

        @AttributeDefinition(name = "service ranking",
                description = "The ranking to cascade the various loggers.")
        int service_ranking() default 9000;

        @AttributeDefinition()
        String webconsole_configurationFactory_nameHint() default
                "{name} (enabled: {enabled}, file: '{logfile.base}', uri: '{pattern.uri.matching}', rank: {service.ranking})";
    }

    protected BundleContext bundleContext;

    protected Config config;

    protected List<Pattern> matchingUriSet;
    protected List<Pattern> ignoredUriSet;

    protected Map<String, RequestLog> modeLogs = new HashMap<>();

    @Activate
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        matchingUriSet = new ArrayList<>();
        ignoredUriSet = new ArrayList<>();
        for (String rule : config.pattern_uri_matching()) {
            if (StringUtils.isNotBlank(rule)) {
                matchingUriSet.add(Pattern.compile(rule));
            }
        }
        for (String rule : config.pattern_uri_ignore()) {
            if (StringUtils.isNotBlank(rule)) {
                ignoredUriSet.add(Pattern.compile(rule));
            }
        }
    }

    @Deactivate
    void deactivate() {
        for (RequestLog log : modeLogs.values()) {
            log.close();
        }
        modeLogs.clear();
    }

    protected boolean matches(@Nonnull final List<Pattern> patternSet, @Nonnull final String uri) {
        for (Pattern pattern : patternSet) {
            if (pattern.matcher(uri).matches()) {
                return true;
            }
        }
        return false;
    }

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
        return config.logfile_base() + modeKey + ".log";
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
                .append(") ")
                .append(duration)
                .append("ms '")
                .append(referer != null ? referer : "-")
                .append("' {")
                .append(userAgent != null ? userAgent : "?")
                .append('}');
    }

    @Override
    public String getName() {
        return config.name();
    }

    @Override
    public boolean canHandle(@Nullable final AccessMode accessMode,
                             @Nonnull final SlingHttpServletRequest request,
                             @Nonnull final SlingHttpServletResponse response) {
        return config.enabled() && matches(matchingUriSet, request.getRequestURI());
    }

    @Override
    public void logRequest(@Nullable final AccessMode accessMode,
                           @Nonnull final SlingHttpServletRequest request,
                           @Nonnull final SlingHttpServletResponse response) {
        String uri = request.getRequestURI();
        if (!matches(ignoredUriSet, uri)) {
            try {
                StringBuilder builder = new StringBuilder();
                logMessage(builder, request, response);
                getLog(accessMode).write(builder.toString());
            } catch (IOException ex) {
                LOG.error(ex.getMessage(), ex);
            }
        } else {
            LOG.debug("ignored: '{}'", uri);
        }
    }
}

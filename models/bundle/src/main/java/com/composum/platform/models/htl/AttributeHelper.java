package com.composum.platform.models.htl;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.sightly.Record;
import org.apache.sling.scripting.sightly.render.RenderContext;

import javax.script.Bindings;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Allows setting and reading request-/session-attributes, script bindings or a simulated pageContext ({@link
 * EmulatedPageContext}). If there should be something written, the parameter <code>scope</code> should be one of
 * bindings, page, request, session (page being the {@link EmulatedPageContext}), and there can be a <code>key</code>
 * and <code>value</code> parameter to set one value, or an arbitrary number key1, key2, key3 ... and corresponding
 * value1, value2, value3, ... parameters to set the values in that scope.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public class AttributeHelper implements ExtendedUse {

    /** Parameter that determines the scope: lower case string representation of {@link Scope}. */
    public static final String PARAM_SCOPE = "scope";

    public enum Scope {
        BINDINGS,
        /** A page context simulated by {@link EmulatedPageContext}. */
        PAGE,
        REQUEST,
        SESSION
    }

    protected Bindings bindings;

    protected static final Pattern KEYPATTERN = Pattern.compile("key[0-9]*");

    @Override
    public void init(RenderContext renderContext, Bindings arguments) {
        this.bindings = renderContext.getBindings();
        if (null != arguments.get(PARAM_SCOPE)) {
            Scope scope = Scope.valueOf(arguments.get(PARAM_SCOPE).toString().toUpperCase());
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (entry.getKey().startsWith("key") && KEYPATTERN.matcher(entry.getKey()).matches()) {
                    setAttribute(scope, bindings.get(entry.getKey()),
                            bindings.get(entry.getKey().replaceAll("key", "value")));
                }
            }
        }
    }

    protected void setAttribute(Scope scope, Object rawKey, Object value) {
        if (null != rawKey) {
            String key = rawKey.toString();
            switch (scope) {
                case BINDINGS:
                    bindings.put(key, value);
                    break;
                case PAGE:
                    getPageContext().put(key, value);
                    break;
                case REQUEST:
                    getRequest().setAttribute(key, value);
                    break;
                case SESSION:
                    HttpSession session = getRequest().getSession(false);
                    if (session == null) {
                        throw new IllegalStateException("No session");
                    }
                    session.setAttribute(key, value);
                    break;
                default: // Impossible
                    throw new IllegalArgumentException("Unknown scope " + scope);
            }
        }
    }

    protected SlingHttpServletRequest getRequest() {
        return (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
    }

    /** The {@link EmulatedPageContext} for the script. */
    public Map<String, Object> getPageContext() {
        return EmulatedPageContext.map(bindings);
    }

    /** The map of request parameters. Caution: the values are arrays. */
    public Map<String, String[]> getRequestParameters() {
        return getRequest().getParameterMap();
    }

    /** Provides a HTL readable view of the request attributes. */
    public Record<Object> getRequestAttributes() {
        final SlingHttpServletRequest request = getRequest();
        return new Record<Object>() {
            @Override
            public Object getProperty(String name) {
                return request.getAttribute(name);
            }

            @Override
            public Set<String> getPropertyNames() {
                Set<String> res = new LinkedHashSet<>();
                Enumeration<String> enumeration = request.getAttributeNames();
                while (enumeration.hasMoreElements()) res.add(enumeration.nextElement());
                return res;
            }
        };
    }

    /** Provides a HTL readable view of the session Attributes. */
    public Record<Object> getSessionAttributes() {
        final HttpSession session = getRequest().getSession(false);
        if (null == session) return null;
        return new Record<Object>() {
            @Override
            public Object getProperty(String name) {
                return session.getAttribute(name);
            }

            @Override
            public Set<String> getPropertyNames() {
                Set<String> res = new LinkedHashSet<>();
                Enumeration<String> enumeration = session.getAttributeNames();
                while (enumeration.hasMoreElements()) res.add(enumeration.nextElement());
                return res;
            }
        };
    }

}

package com.composum.platform.models.htl;

import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScript;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.el.ELContext;
import javax.script.Bindings;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.el.ExpressionEvaluator;
import javax.servlet.jsp.el.VariableResolver;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Map;

import static com.composum.sling.core.BeanContext.Scope.session;

/** Mocks a {@link PageContext} from the {@link Bindings} of HTL. */
public class HtlPageContext extends PageContext {

    private static final Logger LOG = LoggerFactory.getLogger(HtlPageContext.class);

    protected final Bindings bindings;
    protected Map<String, Object> emulatedPageContext;

    /**
     * Instantiates a new HTLPageContext.
     *
     * @param bindings the HTL bindings
     */
    public HtlPageContext(@Nonnull Bindings bindings) {
        this.bindings = bindings;
        emulatedPageContext = EmulatedPageContext.map(bindings);
        setAttribute(SlingBindings.RESOURCE, bindings.get(SlingBindings.RESOURCE));
        if (null == getAttribute(SlingBindings.class.getName())) {
            SlingBindings res = new SlingBindings();
            res.putAll(bindings);
            setAttribute(SlingBindings.class.getName(), res);
        }
    }

    protected SlingScriptHelper getSlingScriptHelper() {
        return (SlingScriptHelper) bindings.get(SlingBindings.SLING);
    }

    protected SlingScript getSlingScript() {
        return getSlingScriptHelper().getScript();
    }

    @Override
    public void release() {
    }

    @Override
    public HttpSession getSession() {
        return ((HttpServletRequest) getRequest()).getSession(false);
    }

    @Override
    public ServletRequest getRequest() {
        return (ServletRequest) bindings.get(SlingBindings.REQUEST);
    }

    @Override
    public ServletResponse getResponse() {
        return (ServletResponse) bindings.get(SlingBindings.RESPONSE);
    }

    protected JspWriter jspWriter;

    @Override
    public JspWriter getOut() {
        if (null == jspWriter)
            jspWriter = new JspWriterPrintWriterWrapper((PrintWriter) bindings.get(SlingBindings.OUT));
        return jspWriter;
    }

    @Override
    public void setAttribute(String name, Object attribute) {
        if (attribute != null) {
            emulatedPageContext.put(name, attribute);
        } else {
            removeAttribute(name, PAGE_SCOPE);
        }
    }

    @Override
    public void setAttribute(String name, Object o, int scope) {
        if (o != null) {
            switch (scope) {
                case PAGE_SCOPE:
                    emulatedPageContext.put(name, o);
                    break;

                case REQUEST_SCOPE:
                    getRequest().setAttribute(name, o);
                    break;

                case SESSION_SCOPE:
                    HttpSession session = getSession();
                    if (session == null) {
                        throw new IllegalStateException("No session");
                    }
                    session.setAttribute(name, o);
                    break;

                case APPLICATION_SCOPE:
                    throw new UnsupportedOperationException("No application scope implemented.");

                default:
                    throw new IllegalArgumentException("Invalid scope");
            }
        } else {
            removeAttribute(name, scope);
        }
    }

    @Override
    public Object getAttribute(String name) {
        return bindings.get(name);
    }

    @Override
    public Object getAttribute(String name, int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                return emulatedPageContext.get(name);

            case REQUEST_SCOPE:
                return getRequest().getAttribute(name);

            case SESSION_SCOPE:
                HttpSession session = getSession();
                if (session == null) {
                    throw new IllegalStateException("No session");
                }
                return session.getAttribute(name);

            case APPLICATION_SCOPE:
                throw new UnsupportedOperationException("No application scope implemented.");

            default:
                throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public Object findAttribute(String name) {
        Object o = emulatedPageContext.get(name);
        if (o != null)
            return o;

        o = getRequest().getAttribute(name);
        if (o != null)
            return o;

        HttpSession session = getSession();
        if (session != null) {
            o = session.getAttribute(name);
            if (o != null)
                return o;
        }

        // no application scope implemented.
        return null;
    }

    @Override
    public void removeAttribute(String name) {
        try {
            removeAttribute(name, PAGE_SCOPE);
            removeAttribute(name, REQUEST_SCOPE);
            if (session != null) {
                removeAttribute(name, SESSION_SCOPE);
            }
            removeAttribute(name, APPLICATION_SCOPE);
        } catch (Exception ex) {
            TagHtlAdapter.LOG.debug("Trouble removing " + name + " : " + ex);
            // remove as much as possible
        }
    }

    @Override
    public void removeAttribute(String name, int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                emulatedPageContext.remove(name);
                break;

            case REQUEST_SCOPE:
                getRequest().removeAttribute(name);
                break;

            case SESSION_SCOPE:
                HttpSession session = getSession();
                if (session == null) {
                    throw new IllegalStateException("No session");
                }
                session.removeAttribute(name);
                break;

            case APPLICATION_SCOPE:
                throw new UnsupportedOperationException("No application scope implemented.");

            default:
                throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public int getAttributesScope(String name) {
        if (emulatedPageContext.get(name) != null)
            return PAGE_SCOPE;
        if (getRequest().getAttribute(name) != null)
            return REQUEST_SCOPE;

        HttpSession session = getSession();
        if (session != null) {
            if (session.getAttribute(name) != null)
                return SESSION_SCOPE;
        }
        // no application scope implemented
        return 0;
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                return IteratorUtils.asEnumeration(emulatedPageContext.keySet().iterator());

            case REQUEST_SCOPE:
                return getRequest().getAttributeNames();

            case SESSION_SCOPE:
                HttpSession session = getSession();
                if (session == null) {
                    throw new IllegalStateException("No session");
                }
                return session.getAttributeNames();

            case APPLICATION_SCOPE:
                throw new UnsupportedOperationException("No application scope implemented.");

            default:
                throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public void forward(String relativeUrlPath) throws ServletException, IOException {
        getSlingScriptHelper().forward(relativeUrlPath);
    }

    @Override
    public void include(String relativeUrlPath) throws ServletException, IOException {
        getSlingScriptHelper().include(relativeUrlPath);
    }

    @Override
    public void include(String relativeUrlPath, boolean flush) throws ServletException, IOException {
        if (flush) jspWriter.flush();
        getSlingScriptHelper().include(relativeUrlPath);
    }

    @Override
    public Object getPage() {
        return getSlingScript();
    }

    @Override
    public ServletConfig getServletConfig() {
        // actually a DefaultSlingScript that implements this
        return (ServletConfig) getSlingScript();
    }

    @Override
    public ServletContext getServletContext() {
        return getServletConfig().getServletContext();
    }

    // These methods might be needed for the Composum tags.
    @Override
    public ExpressionEvaluator getExpressionEvaluator() { // FIXME hps probably used in composum tags
        throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.getExpressionEvaluator");
    }

    @Override
    public VariableResolver getVariableResolver() {
        throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.getVariableResolver");
    }

    @Override
    public ELContext getELContext() {
        throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.getVariableResolver");
    }

    /** Not used, since not instantiated by the container. */
    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response, String
            errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) throws IOException,
            IllegalStateException, IllegalArgumentException {
        throw new UnsupportedOperationException("Unused: SimulatedPageContext.initialize");
    }

    @Override
    public Exception getException() {
        return null;
    }

    @Override
    public void handlePageException(Exception e) throws ServletException, IOException {
        if (e instanceof ServletException) throw (ServletException) e;
        if (e instanceof IOException) throw (IOException) e;
        throw new ServletException(e);
    }

    @Override
    public void handlePageException(Throwable t) throws ServletException, IOException {
        if (t instanceof ServletException) throw (ServletException) t;
        if (t instanceof IOException) throw (IOException) t;
        if (t instanceof Error) throw (Error) t;
        throw new ServletException(t);
    }

}

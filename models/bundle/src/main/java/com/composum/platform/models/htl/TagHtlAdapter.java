package com.composum.platform.models.htl;

import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScript;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.commons.classloader.DynamicClassLoaderManager;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.slf4j.Logger;

import javax.el.ELContext;
import javax.script.Bindings;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.el.ExpressionEvaluator;
import javax.servlet.jsp.el.VariableResolver;
import javax.servlet.jsp.tagext.BodyTag;
import javax.servlet.jsp.tagext.Tag;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Map;

import static com.composum.sling.core.BeanContext.Scope.session;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Crude wrapper for JSP tags to use these in HTL scripts.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public class TagHtlAdapter implements Use {

    /** String parameter {@value #TAGCLASS} that specifies the class name of the tag. */
    public static final String TAGCLASS = "adapterTagClass";
    /** {@link java.util.Deque} Used to store uncompleted tags in {@link Bindings}. */
    public static final String BINDINGATTR_TAGSTACK = TagHtlAdapter.class.getName() + ".tagStack";

    protected static final Logger LOG = getLogger(TagHtlAdapter.class);
    protected Logger jspLog;
    protected Bindings bindings;
    protected Class<? extends Tag> tagClass;
    protected Deque<Tag> tagStack;

    @Override
    public void init(Bindings bindings) {
        this.bindings = bindings;
        SlingScriptHelper helper = (SlingScriptHelper) bindings.get(SlingBindings.SLING);
        ClassLoader classLoader = helper.getService(DynamicClassLoaderManager.class).getDynamicClassLoader();
        String tagclassname = (String) bindings.get(TAGCLASS);
        try {
            tagClass = (Class<? extends Tag>) classLoader.loadClass(tagclassname);
        } catch (ReflectiveOperationException e) {
            LOG.error("Could not find tag " + tagclassname, e);
            throw new IllegalArgumentException("Could not find or create tag " + tagclassname, e);
        }
        ServletRequest request = (ServletRequest) bindings.get(SlingBindings.REQUEST);
        tagStack = (Deque<Tag>) request.getAttribute(BINDINGATTR_TAGSTACK);
        if (null == tagStack) request.setAttribute(BINDINGATTR_TAGSTACK, tagStack = new ArrayDeque<>());
        jspLog = (Logger) bindings.get(SlingBindings.LOG);
    }

    /**
     * Triggers the processing of both the start and end of the tag - for tags without body.
     *
     * @return the empty string for easy triggering this in the page - the generated content is actually written to the
     * JspWriter.
     */
    public String doStartAndEnd() throws JspException {
        return doStart() + doEnd();
    }

    /**
     * Triggers the processing of both the start and end of the tag.
     *
     * @return the empty string for easy triggering this in the page - the generated content is actually written to the
     * JspWriter.
     */
    public String doStart() throws JspException {
        Tag tag;
        try {
            tag = (Tag) tagClass.newInstance();
        } catch (ReflectiveOperationException e) {
            LOG.error("Could not create tag " + tagClass, e);
            if (null != jspLog) jspLog.error("Could not create tag " + tagClass, e);
            throw new IllegalArgumentException("Could not create tag " + tagClass, e);
        }
        try {
            tag.setPageContext(new SimulatedPageContext());
            initProperties(tag);
            tag.doStartTag();
            tagStack.push(tag);
            return "";
        } catch (JspException | RuntimeException e) {
            LOG.error("" + e, e);
            if (null != jspLog) jspLog.error("" + e, e);
            tag.release();
            throw e;
        }
    }

    protected void initProperties(Tag tag) throws JspException {
        for (PropertyDescriptor pd : PropertyUtils.getPropertyDescriptors(tagClass)) {
            String name = pd.getName();
            if (bindings.containsKey(name) && !isIgnoredProperty(name) && null != pd.getWriteMethod()) {
                try {
                    Object value = bindings.get(name);
                    if (null != value) {
                        Object convertedValue = ConvertUtils.convert(value, pd.getPropertyType());
                        PropertyUtils.setProperty(tag, name, convertedValue);
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    LOG.error("Could not set " + pd.getShortDescription(), e);
                    if (null != jspLog) jspLog.error("Could not set " + pd.getShortDescription(), e);
                }
            }
        }
    }

    /**
     * Whether name is a name of a property that should not be set from tag parameters since it is a special method from
     * {@link BodyTag} or something.
     */
    protected boolean isIgnoredProperty(String name) {
        return "bodyContent".equals(name) || "pageContext".equals(name) || "parent".equals(name);
    }

    /**
     * Triggers the processing of both the start and end of the tag.
     *
     * @return the empty string for easy triggering this in the page - the generated content is actually written to the
     * JspWriter.
     */
    public String doEnd() throws JspException {
        if (tagStack.isEmpty() || !tagClass.isInstance(tagStack.peek())) {
            LOG.error("Tag nesting error - expecting {} but got tag stack {}", tagClass, tagStack);
            if (null != jspLog)
                jspLog.error("Tag nesting error - expecting {} but got tag stack {}", tagClass, tagStack);
            while (!tagStack.isEmpty()) tagStack.pop().release(); // stack is broken, anyway
            throw new JspException("Tag nesting error - expecting close of " + tagClass + " but have  " +
                    (tagStack.isEmpty() ? "no open tag" : tagStack.peek().getClass())
            );
        }
        Tag tag = tagStack.pop();
        try {
            tag.doEndTag();
        } catch (JspException | RuntimeException e) {
            LOG.error("" + e, e);
            if (null != jspLog) jspLog.error("" + e, e);
        } finally {
            tag.release();
        }
        return "";
    }

    protected SlingScriptHelper getSlingScriptHelper() {
        return (SlingScriptHelper) bindings.get(SlingBindings.SLING);
    }

    protected SlingScript getSlingScript() {
        return getSlingScriptHelper().getScript();
    }

    /** Mocks the pagecontext for the tags. To be extended as needed for the tags. */
    protected class SimulatedPageContext extends PageContext {

        private Map<String, Object> emulatedPageContext = EmulatedPageContext.map(bindings);
        {
            setAttribute(SlingBindings.RESOURCE, bindings.get(SlingBindings.RESOURCE));
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
                LOG.debug("Trouble removing " + name + " : " + ex);
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
            throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.getELContext");
        }

        // The following methods can probably not really be supported / make no sense in this context
        @Override
        public void initialize(Servlet servlet, ServletRequest request, ServletResponse response, String
                errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) throws IOException,
                IllegalStateException, IllegalArgumentException {
            throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.initialize");
        }

        @Override
        public Exception getException() {
            throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.getException");
        }

        @Override
        public void handlePageException(Exception e) throws ServletException, IOException {
            throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.handlePageException");
        }

        @Override
        public void handlePageException(Throwable t) throws ServletException, IOException {
            throw new UnsupportedOperationException("Not implemented yet: SimulatedPageContext.handlePageException");
        }

    }
}

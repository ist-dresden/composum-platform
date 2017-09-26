package com.composum.platform.models.htl;

import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.commons.classloader.DynamicClassLoaderManager;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.slf4j.Logger;

import javax.script.Bindings;
import javax.servlet.ServletRequest;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.BodyTag;
import javax.servlet.jsp.tagext.Tag;
import java.beans.PropertyDescriptor;
import java.util.ArrayDeque;
import java.util.Deque;

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
        boolean rendered = false;
        try {
            doStart();
            rendered = true;
        } catch (JspException | RuntimeException e) {
            // we don't call doEnd, but since exceptions don't stop the rendering we remove the tag
            // from the stack for less confusion
            if (!tagStack.isEmpty() && tagClass.isInstance(tagStack.peek())) tagStack.pop();
            throw e;
        }
        return doEnd();
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
            tagStack.push(tag); // exceptions won't stop rendering, so we push it anyway for less confusion
            makePageContext(tag);
            initProperties(tag);
            tag.doStartTag();
            return "";
        } catch (JspException | RuntimeException e) {
            LOG.error("" + e, e);
            if (null != jspLog) jspLog.error("" + e, e);
            tag.release();
            throw e;
        }
    }

    protected void makePageContext(Tag tag) {
        tag.setPageContext(new HtlPageContext(bindings));
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
            throw e;
        } finally {
            tag.release();
        }
        return "";
    }

}

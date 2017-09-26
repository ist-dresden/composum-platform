package com.composum.platform.models.htl;

import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.sightly.Record;
import org.apache.sling.scripting.sightly.pojo.Use;

import javax.script.Bindings;
import javax.servlet.ServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implements a kind of pagecontext for HTL by putting a map into a request attribute, to enable communication between
 * templates. There are separate maps for each script name - that is, the resource of the script.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public class EmulatedPageContext implements Use, Record<Object> {

    protected Map<String, Object> simulatedPageContext;

    /**
     * Map&lt;String,Map&lt;String,Object&gt;&gt; from scriptname to simulated pagecontext map, since the scriptname is
     * the script resource and the bindings are generated freshly each call.
     */
    public static final String REQUESTATTR_PAGECONTEXTMAP = EmulatedPageContext.class.getName() + ".pagecontexts";

//    /** If used as a model, this parameter can be used to put something at this key. */
//    public static final String PARAM_KEY = "key";
//    /** If used as a model, this parameter can give a value to be put at {@link #PARAM_KEY}. */
//    public static final String PARAM_VALUE = "value";

    /**
     * Initializes the maps.
     */
    @Override
    public void init(Bindings bindings) {
        ServletRequest request = (ServletRequest) bindings.get(SlingBindings.REQUEST);
        Map<String, Map<String, Object>> pageContextMap = (Map<String, Map<String, Object>>)
                request.getAttribute(REQUESTATTR_PAGECONTEXTMAP);
        if (null == pageContextMap) {
            pageContextMap = new HashMap<>();
            request.setAttribute(REQUESTATTR_PAGECONTEXTMAP, pageContextMap);
        }
        String scriptName = scriptName(bindings);
        simulatedPageContext = pageContextMap.get(scriptName);
        if (null == simulatedPageContext) {
            simulatedPageContext = new HashMap<>();
            pageContextMap.put(scriptName, simulatedPageContext);
        }
//        if (null != bindings.get(PARAM_KEY)) {
//            simulatedPageContext.put(bindings.get(PARAM_KEY).toString(), bindings.get(PARAM_VALUE));
//        }
    }

    protected static String scriptName(Bindings bindings) {
        SlingScriptHelper helper = (SlingScriptHelper) bindings.get(SlingBindings.SLING);
        return helper.getScript().getScriptResource().getPath();
    }

    /** Allows easy access to the map when used in Java Code: returns the actual map used. */
    public static Map<String, Object> map(Bindings bindings) {
        EmulatedPageContext ctx = new EmulatedPageContext();
        ctx.init(bindings);
        return ctx.getPageContext();
    }

    @Override
    public Object getProperty(String name) {
        return simulatedPageContext.get(name);
    }

    @Override
    public Set<String> getPropertyNames() {
        return simulatedPageContext.keySet();
    }

    /** Returns the emulated page context as a map. */
    public Map<String, Object> getPageContext() {
        return simulatedPageContext;
    }
}

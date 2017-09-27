package com.composum.platform.models.htl;

import org.apache.sling.scripting.sightly.pojo.Use;

import javax.script.Bindings;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTL Helper that creates a map (this object itself), e.g. for setting attributes in a data-sly-include /
 * data-sly-resource argument requestAttributes. The values are given as parameters <code>key</code> and
 * <value>value</value> or an arbitrary number key1, key2, key3 ... and corresponding value1, value2, value3, ...
 * parameters to set the values in that scope.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public class MapCreator extends HashMap<String, Object> implements Use {
    protected static final Pattern KEYPATTERN = Pattern.compile("key[0-9]*");

    @Override
    public void init(Bindings bindings) {
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            if (entry.getKey().startsWith("key") && KEYPATTERN.matcher(entry.getKey()).matches()) {
                put(bindings.get(entry.getKey()).toString(), bindings.get(entry.getKey().replaceAll("key", "value")));
            }
        }
    }

}

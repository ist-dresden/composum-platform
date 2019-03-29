package com.composum.sling.platform.staging.testutil;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.hamcrest.Matchers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.StringJoiner;

/** Extends the {@link AssertionCodeGenerator} with some Sling specific stuff. */
public class SlingAssertionCodeGenerator extends AssertionCodeGenerator {

    public SlingAssertionCodeGenerator(@Nonnull String variableName, @Nullable Object object) {
        super(variableName, object);
    }

    @Override
    protected void createMatcherHook(@Nonnull Object value) {
        if (value instanceof Resource) {
            assertionBuf.append("hasResourcePath(");
            appendQuotedString(((Resource) value).getPath());
            assertionBuf.append(")");
        } else if (value instanceof ResourceResolver) { // nothing to compare here
            assertionBuf.append("notNullValue(ResourceResolver.class)");
        } else {
            super.createMatcherHook(value);
        }
    }

    @Override
    protected void createMatcherForIterable(List contents) {
        if (Matchers.everyItem(Matchers.isA(String.class)).matches(contents)) {
            if (contents.isEmpty()) {
                assertionBuf.append("emptyIterable()");
            } else {
                assertionBuf.append("contains(");
                boolean first = true;
                for (Object str : contents) {
                    if (!first) assertionBuf.append(",");
                    appendQuotedString(String.valueOf(str));
                    first = false;
                }
                assertionBuf.append(")");
            }
        } else if (Matchers.everyItem(Matchers.isA(Resource.class)).matches(contents)) {
            assertionBuf.append("mappedMatches(SlingMatchers::resourcePaths,");
            createMatcherForIterable(SlingMatchers.resourcePaths(contents));
            assertionBuf.append(")");
        } else {
            super.createMatcherForIterable(contents);
        }
    }

}

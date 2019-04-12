package com.composum.sling.platform.testing.testutil;

import org.junit.Test;

import static org.junit.Assert.*;

/** Test for {@link AnnotationWithDefaults}. */
public class AnnotationWithDefaultsTest {

    private @interface SomeAnnotation {
        String[] aStringArray() default {"foo", "bar"};

        String aString() default "baz";

        int noDefaultInt();

        String noDefaultString();
    }

    @Test
    public void annotationWithDefault() {
        SomeAnnotation annotation = AnnotationWithDefaults.of(SomeAnnotation.class);
        assertEquals("baz", annotation.aString());
        assertArrayEquals(new String[]{"foo", "bar"}, annotation.aStringArray());
        assertNull(annotation.noDefaultString());
        assertTrue(0 == annotation.noDefaultInt());
    }

}

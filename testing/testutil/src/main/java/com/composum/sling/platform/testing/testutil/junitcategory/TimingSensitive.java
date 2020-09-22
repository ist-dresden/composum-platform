package com.composum.sling.platform.testing.testutil.junitcategory;

/**
 * Category for Junit tests that are sensitive to timing - which might fail on slow / congested machines like Travis.
 * Could be used to ignore them.
 *
 * @see org.junit.experimental.categories.Category
 */
public interface TimingSensitive {
}

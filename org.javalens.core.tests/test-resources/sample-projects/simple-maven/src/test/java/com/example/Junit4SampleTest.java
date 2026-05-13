package com.example;

import org.junit.Before;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Sample test class for testing FindTestsTool against JUnit 4 annotations.
 * Imports are unresolved (simple-maven has no JUnit 4 dependency), which is
 * fine — JDT parses the AST regardless and the tool detects @Test by simple
 * name. Framework attribution falls through to the @Before/@After heuristic.
 */
public class Junit4SampleTest {

    @Before
    public void setUp() {
        // JUnit 4 lifecycle annotation — triggers the JUnit4 framework heuristic.
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testAddition() {
    }

    @Test
    public void testSubtraction() {
    }

    @Test
    @Ignore("not implemented")
    public void testIgnored() {
    }
}

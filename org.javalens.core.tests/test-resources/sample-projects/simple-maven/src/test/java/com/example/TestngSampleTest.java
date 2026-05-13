package com.example;

/**
 * Sample test class for testing FindTestsTool against TestNG annotations.
 * Uses fully qualified `@org.testng.annotations.*` so the tool's framework
 * detection (which checks for "testng" in the annotation type name string)
 * picks it up. The simple-maven fixture has no TestNG dependency; the
 * annotation imports are intentionally absent.
 */
public class TestngSampleTest {

    @org.testng.annotations.BeforeMethod
    public void setUp() {
    }

    @org.testng.annotations.AfterMethod
    public void tearDown() {
    }

    @org.testng.annotations.Test
    public void scenarioOne() {
    }

    @org.testng.annotations.Test
    public void scenarioTwo() {
    }
}

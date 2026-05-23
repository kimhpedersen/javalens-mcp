package com.example.sharedlib;

/**
 * Type that the root build's App is expected to consume across the
 * includeBuild boundary. The test verifies the fixture's two builds
 * load without crashing JavaLens.
 */
public class SharedUtil {
    public static String greet(String name) {
        return "Hello, " + name;
    }
}

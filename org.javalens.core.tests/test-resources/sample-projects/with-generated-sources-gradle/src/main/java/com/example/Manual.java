package com.example;

/**
 * Hand-written class that references {@code Generated.HELLO}, which lives in
 * {@code build/generated/sources/annotationProcessor/main/java/com/example/Generated.java}.
 * The test creates Generated.java before loading the project; if the discovery
 * doesn't pick up Gradle-style generated sources, the reference here is unresolved.
 */
public class Manual {
    public String hello() {
        return Generated.HELLO;
    }
}

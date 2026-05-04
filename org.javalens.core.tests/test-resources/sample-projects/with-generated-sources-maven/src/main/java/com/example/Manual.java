package com.example;

/**
 * Hand-written class that references {@code Generated.HELLO}, which lives in
 * {@code target/generated-sources/annotations/com/example/Generated.java}.
 * The test creates Generated.java before loading the project; if the
 * BuildSystemAdapter doesn't add target/generated-sources/* as a source folder,
 * the reference here is unresolved.
 */
public class Manual {
    public String hello() {
        return Generated.HELLO;
    }
}

package com.example;

/**
 * Exercises Java 25 flexible constructor bodies (JEP 513): a statement appears
 * before the explicit {@code super()} invocation. This is a syntax error prior to
 * Java 25, so JDT parsing it cleanly at compliance 25 proves Java 25 support.
 */
public class FlexibleCtorDemo {

    private final int value;

    public FlexibleCtorDemo(int v) {
        if (v < 0) {
            throw new IllegalArgumentException("v must be >= 0");
        }
        super();
        this.value = v;
    }

    public int value() {
        return value;
    }
}

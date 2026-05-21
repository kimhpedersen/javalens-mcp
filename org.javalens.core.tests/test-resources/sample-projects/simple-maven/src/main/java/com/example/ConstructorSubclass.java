package com.example;

/**
 * Subclass of ConstructorTarget that invokes super(name, count) explicitly.
 * Used for change_method_signature SuperConstructorInvocation propagation tests.
 */
public class ConstructorSubclass extends ConstructorTarget {

    private final String extra;

    public ConstructorSubclass(String name, int count, String extra) {
        super(name, count);
        this.extra = extra;
    }

    public String getExtra() {
        return extra;
    }
}

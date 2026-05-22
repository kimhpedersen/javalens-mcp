package com.example.genericunused;

/**
 * Non-generic abstract class. Private field is read in a method body.
 * Establishes that abstract-ness alone does NOT cause a false positive.
 */
public abstract class AbstractPlainClass {
    private int value;

    public int read() {
        return value;
    }
}

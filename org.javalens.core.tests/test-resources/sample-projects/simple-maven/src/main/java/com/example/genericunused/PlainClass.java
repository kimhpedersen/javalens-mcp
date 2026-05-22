package com.example.genericunused;

/**
 * Non-generic concrete class. Private field is read in a method body.
 * Establishes the negative-control baseline: find_unused_code must NOT
 * flag `value` here. Paired with GenericClass to isolate the declaring
 * class's genericity as the only varying dimension.
 */
public class PlainClass {
    private int value;

    public int read() {
        return value;
    }
}

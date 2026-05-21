package com.example;

/**
 * Parent class with a method that a subclass invokes via super.method(...).
 * Used for change_method_signature SuperMethodInvocation propagation tests.
 */
public class SuperMethodParent {

    public String greet(String name) {
        return "Hello, " + name;
    }
}

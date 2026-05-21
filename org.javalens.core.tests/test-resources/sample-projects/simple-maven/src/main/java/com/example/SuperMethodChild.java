package com.example;

/**
 * Subclass that invokes super.greet(name) — the SuperMethodInvocation AST node
 * form, distinct from MethodInvocation. Does NOT override greet, so changing the
 * parent's signature affects only the super-call site (not an override decl).
 * Used for change_method_signature propagation tests.
 */
public class SuperMethodChild extends SuperMethodParent {

    public String enthusiasticGreet(String name) {
        return super.greet(name) + "!";
    }
}

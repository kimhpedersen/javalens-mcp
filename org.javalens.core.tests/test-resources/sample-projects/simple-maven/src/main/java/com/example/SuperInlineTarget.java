package com.example;

/**
 * Fixture: a method whose body calls super.toString(). When this method is
 * inlined into a call site in a SUBCLASS, the `super` reference's meaning
 * changes — it would refer to the subclass's superclass (this class) rather
 * than to this class's superclass (Object). Used to test inline_method's
 * detection of super-references in the body.
 */
public class SuperInlineTarget {

    public String label() {
        return super.toString() + ":labeled";
    }
}

package com.example.genericunused;

/**
 * Generic abstract class. The private field is DELIBERATELY a non-T type
 * (String) so the failure is attributable to the declaring class's
 * genericity alone, not to the field's type. Issue #17 shows this is
 * sufficient to reproduce the binding-equality mismatch.
 */
public abstract class AbstractGenericClass<T> {
    private String name;

    public boolean handles(String other) {
        return name.equals(other);
    }
}

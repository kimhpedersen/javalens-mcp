package com.example;

import java.util.List;

/**
 * Test fixture for extract_interface across generic-class and generic-method
 * dimensions. The class declares a type parameter T whose erasure is bounded.
 * One of its methods declares its own method-level type parameter U.
 */
public class GenericInterfaceExtractTarget<T extends Number> {

    private T value;

    public GenericInterfaceExtractTarget(T value) {
        this.value = value;
    }

    /**
     * Returns the stored value. The return type uses the class-level T.
     */
    public T get() {
        return value;
    }

    /**
     * Replaces the stored value. The parameter type uses the class-level T.
     */
    public void set(T value) {
        this.value = value;
    }

    /**
     * A method-level generic method: declares its own type parameter U
     * whose bound is independent of T.
     */
    public <U extends Comparable<U>> U identity(U input) {
        return input;
    }

    /**
     * Composed signature: parameter uses class-level T and method-level U,
     * return type uses both.
     */
    public <U> List<T> pair(U key) {
        return List.of(value);
    }
}

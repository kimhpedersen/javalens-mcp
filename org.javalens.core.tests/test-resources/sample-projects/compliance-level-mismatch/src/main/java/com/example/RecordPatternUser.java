package com.example;

/**
 * Uses a Java 21 record pattern (JEP 440) which is only valid at source level 21+.
 * If JavaLens does not apply the project's declared compiler source level to the
 * IJavaProject, JDT defaults can flag this as a syntax error.
 */
public class RecordPatternUser {

    public record Box(Integer value) {}

    public int unwrap(Object obj) {
        // Record pattern (Java 21) — requires source/target/release = 21
        if (obj instanceof Box(Integer i)) {
            return i;
        }
        return -1;
    }
}

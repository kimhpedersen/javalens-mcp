package com.example;

/**
 * Plain Java 17-compatible code. The fixture's pom declares source level 17, deliberately
 * different from the JDT workspace default (typically 21 in this Eclipse target) so the
 * compliance test can prove the per-project option was read from pom rather than silently
 * inheriting the workspace default.
 */
public class RecordPatternUser {

    public record Box(Integer value) {}

    public int unwrap(Object obj) {
        if (obj instanceof Box) {
            return ((Box) obj).value();
        }
        return -1;
    }
}

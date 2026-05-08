package com.example;

/**
 * Plain Java project — no pom.xml / build.gradle / BUILD.bazel. The compliance fallback
 * uses {@link Runtime#version()}.feature() so analysis still parses modern syntax instead
 * of inheriting an older JDT default.
 */
public class PlainHello {
    public String greet(String name) {
        return "hello, " + name;
    }
}

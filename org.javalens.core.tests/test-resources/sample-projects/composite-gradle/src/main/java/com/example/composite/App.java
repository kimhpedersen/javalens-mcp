package com.example.composite;

/**
 * Root build's main class. The composite includes shared-lib as a sibling
 * build via {@code includeBuild 'shared-lib'} in settings.gradle. With a
 * proper Gradle resolve this class would call into shared-lib; the fixture
 * keeps the body local so the test doesn't depend on running gradle.
 */
public class App {
    public int counter;
}

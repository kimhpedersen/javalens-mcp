package com.example;

/**
 * Top-level enum fixture for find_large_classes. Declares enough methods
 * that low thresholds should flag it. Enums are AbstractTypeDeclaration
 * just like classes, but JDT models them as EnumDeclaration — a tool
 * that only iterates TypeDeclaration misses them entirely.
 */
public enum LargeEnum {
    ALPHA,
    BETA,
    GAMMA;

    public int one() { return 1; }
    public int two() { return 2; }
    public int three() { return 3; }
    public int four() { return 4; }
    public int five() { return 5; }
    public int six() { return 6; }
    public int seven() { return 7; }
    public int eight() { return 8; }
}

package com.example;

/**
 * Fixture providing multiple (String) cast sites for fine-grain search tests that
 * need a cap-and-truncate scenario. Existing per-type counts assume String casts only
 * in SearchPatterns; this file adds known additional sites.
 */
public class StringCasts {

    public String castA(Object obj) {
        return (String) obj;
    }

    public String castB(Object obj) {
        return (String) obj;
    }

    public String castC(Object obj) {
        return (String) obj;
    }
}

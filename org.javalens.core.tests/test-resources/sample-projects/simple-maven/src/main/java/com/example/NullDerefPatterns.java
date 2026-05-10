package com.example;

public class NullDerefPatterns {

    public int dereferenceWithoutCheck(String s) {
        return s.length();
    }

    public int derefereneAfterAssignedNull() {
        String s = null;
        return s.length();
    }

    public int safeNullCheck(String s) {
        if (s == null) {
            return 0;
        }
        return s.length();
    }

    public int conditionalDeref(String s) {
        if (s != null && s.length() > 0) {
            return s.charAt(0);
        }
        return -1;
    }
}

package com.example;

public class ConstructorTarget {

    private final String name;
    private final int count;

    public ConstructorTarget(String name) {
        this(name, 0);
    }

    public ConstructorTarget(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public int getCount() {
        return count;
    }
}

package com.example;

public class LombokConsumer {
    public String greet(LombokBean bean) {
        // getName() and getAge() are Lombok-generated; they only exist if APT runs.
        return "Hello " + bean.getName() + " (age " + bean.getAge() + ")";
    }
}

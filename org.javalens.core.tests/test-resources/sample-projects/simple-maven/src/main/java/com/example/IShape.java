package com.example;

public interface IShape {

    void draw();

    default String describe() {
        return "shape";
    }
}

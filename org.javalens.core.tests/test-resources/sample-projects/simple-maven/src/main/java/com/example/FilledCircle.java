package com.example;

public class FilledCircle implements IFillable {

    private final int radius;
    private String fillColor;

    public FilledCircle(int radius, String fillColor) {
        this.radius = radius;
        this.fillColor = fillColor;
    }

    @Override
    public void draw() {
        System.out.println("Drawing circle r=" + radius);
    }

    @Override
    public void fill() {
        System.out.println("Filling circle with " + fillColor);
    }
}

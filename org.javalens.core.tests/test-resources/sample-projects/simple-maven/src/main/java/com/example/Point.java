package com.example;

public record Point(int x, int y) implements Comparable<Point> {

    @Override
    public int compareTo(Point other) {
        int dx = Integer.compare(this.x, other.x);
        return dx != 0 ? dx : Integer.compare(this.y, other.y);
    }

    public double distance(Point other) {
        int dx = this.x - other.x;
        int dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}

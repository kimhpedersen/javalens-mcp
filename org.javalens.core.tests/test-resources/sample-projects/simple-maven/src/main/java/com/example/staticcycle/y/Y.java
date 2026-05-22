package com.example.staticcycle.y;

import static com.example.staticcycle.x.X.xValue;

public class Y {
    public static int yValue() {
        return 2;
    }

    public static int composed() {
        return xValue() * yValue();
    }
}

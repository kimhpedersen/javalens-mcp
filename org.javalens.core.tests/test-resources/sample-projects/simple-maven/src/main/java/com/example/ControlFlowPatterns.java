package com.example;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

public class ControlFlowPatterns {

    public int simpleLinear(int x) {
        int y = x + 1;
        int z = y * 2;
        return z;
    }

    public int ifElse(int x) {
        if (x > 0) {
            return 1;
        } else if (x < 0) {
            return -1;
        } else {
            return 0;
        }
    }

    public int ternary(int x) {
        return x >= 0 ? x : -x;
    }

    public String switchStatement(int day) {
        switch (day) {
            case 1: return "Mon";
            case 2: return "Tue";
            case 3: return "Wed";
            default: return "?";
        }
    }

    public String switchExpression(int day) {
        return switch (day) {
            case 1 -> "Mon";
            case 2 -> "Tue";
            case 3 -> "Wed";
            default -> "?";
        };
    }

    public int forLoop(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    public int whileLoop(int n) {
        int i = 0;
        int sum = 0;
        while (i < n) {
            sum += i;
            i++;
        }
        return sum;
    }

    public int doWhileLoop(int n) {
        int i = 0;
        int sum = 0;
        do {
            sum += i;
            i++;
        } while (i < n);
        return sum;
    }

    public int enhancedForCollection(List<Integer> values) {
        int sum = 0;
        for (Integer v : values) {
            sum += v;
        }
        return sum;
    }

    public int enhancedForArray(int[] values) {
        int sum = 0;
        for (int v : values) {
            sum += v;
        }
        return sum;
    }

    public int multipleReturns(int x) {
        if (x < 0) return -1;
        if (x == 0) return 0;
        if (x < 10) return 1;
        return 2;
    }

    public void throwSingle(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null");
        }
    }

    public void throwMultiple(int x) {
        if (x < 0) throw new IllegalArgumentException("negative");
        if (x > 100) throw new IllegalStateException("too big");
        if (x == 42) throw new RuntimeException("forbidden");
    }

    public void singleCatch() {
        try {
            Integer.parseInt("x");
        } catch (NumberFormatException e) {
            // single
        }
    }

    public void multiCatch() {
        try {
            String s = System.getProperty("nope");
            int n = Integer.parseInt(s);
            if (n < 0) throw new IOException("negative");
        } catch (NumberFormatException | IOException e) {
            // multi
        }
    }

    public void nestedTry() {
        try {
            try {
                Integer.parseInt("x");
            } catch (NumberFormatException inner) {
                throw new IllegalStateException(inner);
            }
        } catch (IllegalStateException outer) {
            // nested
        }
    }

    public String tryWithResources(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        }
    }

    public int deeplyNested(int x) {
        int r = 0;
        if (x > 0) {
            for (int i = 0; i < x; i++) {
                if (i % 2 == 0) {
                    while (r < 100) {
                        r++;
                    }
                }
            }
        }
        return r;
    }
}

package com.example;

import java.util.List;

public class LoopDemo {

    public int sum(List<Integer> nums) {
        int total = 0;
        for (int i = 0; i < nums.size(); i++) {
            total += nums.get(i);
        }
        return total;
    }
}

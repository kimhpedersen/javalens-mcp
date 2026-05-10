package com.example;

import java.util.ArrayList;
import java.util.List;

public class ConstructorCaller {

    public ConstructorTarget makeOne() {
        return new ConstructorTarget("alpha", 1);
    }

    public ConstructorTarget makeOneArg() {
        return new ConstructorTarget("beta");
    }

    public List<ConstructorTarget> makeMany() {
        List<ConstructorTarget> all = new ArrayList<>();
        all.add(new ConstructorTarget("a", 1));
        all.add(new ConstructorTarget("b", 2));
        all.add(new ConstructorTarget("c", 3));
        return all;
    }
}

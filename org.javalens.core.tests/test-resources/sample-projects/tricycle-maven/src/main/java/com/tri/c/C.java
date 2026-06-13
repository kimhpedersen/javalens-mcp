package com.tri.c;

import com.tri.a.A;

/** Package c depends on a — closes the a->b->c->a cycle. */
public class C {

    private A a;

    public A getA() {
        return a;
    }
}

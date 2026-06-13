package com.tri.a;

import com.tri.b.B;

/** Package a depends on b — one edge of the a->b->c->a cycle. */
public class A {

    private B b;

    public B getB() {
        return b;
    }
}

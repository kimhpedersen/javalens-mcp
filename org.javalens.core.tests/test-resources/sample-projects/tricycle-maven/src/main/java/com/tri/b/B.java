package com.tri.b;

import com.tri.c.C;

/** Package b depends on c — one edge of the a->b->c->a cycle. */
public class B {

    private C c;

    public C getC() {
        return c;
    }
}

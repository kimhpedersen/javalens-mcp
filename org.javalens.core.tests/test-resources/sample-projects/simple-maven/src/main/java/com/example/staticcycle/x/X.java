package com.example.staticcycle.x;

import static com.example.staticcycle.y.Y.yValue;

/**
 * Fixture for static-import dependency detection. The cycle between
 * staticcycle.x and staticcycle.y is established ONLY through `import
 * static` declarations, not through regular type imports. Tools that
 * derive package dependencies from import names must strip the static
 * member (and the declaring class) to recover the package.
 */
public class X {
    public static int xValue() {
        return 1 + yValue();
    }
}

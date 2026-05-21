package com.example;

import java.util.List;
import java.util.function.Function;

/**
 * Fixture for find_unused_code: a private method that is referenced ONLY via a
 * method reference (this::format), never via a direct invocation. The
 * find_unused_code tool's usage-detection visitor must recognize method
 * references; otherwise this private method gets flagged as unused.
 */
public class MethodRefOnlyConsumer {

    public List<String> formatAll(List<Integer> values) {
        return values.stream().map(this::format).toList();
    }

    private String format(int n) {
        return "n=" + n;
    }
}

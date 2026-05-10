package com.example;

public class DataFlowPatterns {

    private int instanceField;
    private static int staticField;

    public int declaredReadWritten(int param) {
        int local = 0;
        local = param + 1;
        local++;
        return local;
    }

    public int parameterOnly(int p1, int p2) {
        return p1 + p2;
    }

    public int fieldsAndLocals(int param) {
        int local = param;
        instanceField = local;
        staticField = local + 1;
        return instanceField + staticField;
    }

    public int conditionalReadWrite(int x) {
        int result;
        if (x > 0) {
            result = x;
        } else {
            result = -x;
        }
        return result;
    }

    public int loopVariable(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    public Object multipleReturnsDifferentTypes(int kind) {
        if (kind == 1) return "string";
        if (kind == 2) return 42;
        if (kind == 3) return new int[]{1, 2, 3};
        return null;
    }

    public Runnable effectivelyFinal() {
        int captured = 100;
        return () -> System.out.println(captured);
    }

    public void emptyMethod() {
    }
}

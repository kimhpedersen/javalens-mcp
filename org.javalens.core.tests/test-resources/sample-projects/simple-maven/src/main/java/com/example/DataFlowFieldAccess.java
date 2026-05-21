package com.example;

/**
 * Fixture for analyze_data_flow's qualified-field-access write detection.
 * Uses `this.x = ...` form (FieldAccess on LHS of Assignment) — distinct
 * from the bare-name form `x = ...` that the visitor's immediate-parent
 * check catches naturally.
 */
public class DataFlowFieldAccess {

    private int counter;
    private String label;

    public void writeViaThisQualifier() {
        this.counter = 42;
        this.label = "initialized";
    }

    public void compoundAssignViaThis() {
        this.counter += 5;
    }
}

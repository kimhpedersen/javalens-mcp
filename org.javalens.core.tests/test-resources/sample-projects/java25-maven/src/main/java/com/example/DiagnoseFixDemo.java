package com.example;

import java.util.List;
import java.util.Map;

/// Carries one fixable diagnostic: the Map import is unused (warning), whose
/// top quick fix is remove_import. diagnose_and_fix must surface the problem
/// and the computed removal edit in one response.
public class DiagnoseFixDemo {

    public List<String> values() {
        return List.of("a");
    }
}

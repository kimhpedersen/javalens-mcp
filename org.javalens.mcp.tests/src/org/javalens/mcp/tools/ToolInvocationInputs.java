package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The single source of truth for a known-valid invocation of every registered
 * tool against the simple-maven fixture. Shared by {@code ToolContractParityTest}
 * (drives {@code execute()}) and {@code ProtocolParityTest} (drives the same
 * inputs through the JSON-RPC envelope), so a newly registered tool must be
 * added in exactly one place or both parity gates fail.
 *
 * <p>Calculator.java line 14 / column 15 is the {@code add} method declaration;
 * line 6 / column 16 is the {@code lastResult} field. RefactoringTarget.java
 * line 71 / column 18 is {@code formatMessage}. Type-name inputs use
 * {@code com.example.Calculator}.
 */
public final class ToolInvocationInputs {

    private ToolInvocationInputs() {
    }

    public static Map<String, ObjectNode> buildValidInputs(ObjectMapper objectMapper, Path projectPath) {
        String calcPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        String refTarget = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        String bugPatterns = projectPath.resolve("src/main/java/com/example/BugPatterns.java").toString();

        Map<String, ObjectNode> m = new HashMap<>();
        // Symbol-position inputs (filePath + line + column).
        for (String name : new String[]{
            "go_to_definition", "find_references", "find_implementations",
            "rename_symbol", "get_hover_info", "get_method_at_position",
            "get_type_at_position", "get_field_at_position", "get_enclosing_element",
            "find_method_references", "get_javadoc", "get_signature_help",
            "get_symbol_info", "get_super_method", "extract_method", "extract_variable",
            "extract_constant", "extract_interface", "inline_method", "inline_variable",
            "find_field_writes", "convert_anonymous_to_lambda",
            "get_call_hierarchy_incoming", "get_call_hierarchy_outgoing",
            "analyze_method", "analyze_change_impact",
            "find_throws_declarations"
        }) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("filePath", calcPath);
            args.put("line", 14);   // 0-based; Calculator.add line
            args.put("column", 15);
            if (name.equals("rename_symbol")) args.put("newName", "addRenamed");
            if (name.equals("extract_method")) args.put("methodName", "extracted");
            if (name.equals("extract_variable")) args.put("variableName", "extracted");
            if (name.equals("extract_constant")) args.put("constantName", "EXTRACTED");
            if (name.equals("extract_interface")) args.put("interfaceName", "ICalculator");
            m.put(name, args);
        }

        // File-only inputs.
        for (String name : new String[]{
            "analyze_file", "organize_imports", "validate_syntax",
            "get_document_symbols", "get_diagnostics", "get_quick_fixes",
            "find_naming_violations", "find_tests", "find_possible_bugs",
            "analyze_control_flow", "analyze_data_flow",
            "get_complexity_metrics"
        }) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("filePath", calcPath);
            if (name.equals("analyze_control_flow") || name.equals("analyze_data_flow")
                || name.equals("get_complexity_metrics")) {
                args.put("methodName", "add");
            }
            if (name.equals("get_quick_fixes")) {
                args.put("line", 0);
                args.put("column", 0);
            }
            m.put(name, args);
        }

        // Type-name inputs.
        for (String name : new String[]{
            "analyze_type", "get_type_hierarchy", "get_type_members",
            "get_type_usage_summary", "find_annotation_usages",
            "find_casts", "find_instanceof_checks",
            "find_type_arguments", "find_type_instantiations",
            "find_catch_blocks"
        }) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("typeName", "com.example.Calculator");
            m.put(name, args);
        }

        // Query inputs.
        ObjectNode searchArgs = objectMapper.createObjectNode();
        searchArgs.put("query", "Calculator");
        m.put("search_symbols", searchArgs);

        ObjectNode suggestArgs = objectMapper.createObjectNode();
        suggestArgs.put("simpleName", "List");
        m.put("suggest_imports", suggestArgs);

        m.put("find_reflection_usage", objectMapper.createObjectNode());
        m.put("find_circular_dependencies", objectMapper.createObjectNode());
        m.put("find_large_classes", objectMapper.createObjectNode());
        m.put("find_unused_code", objectMapper.createObjectNode());
        m.put("find_unreachable_code", objectMapper.createObjectNode());

        ObjectNode findAffectedTestsArgs = objectMapper.createObjectNode();
        findAffectedTestsArgs.put("filePath", calcPath);
        findAffectedTestsArgs.put("line", 14);
        findAffectedTestsArgs.put("column", 15);
        m.put("find_affected_tests", findAffectedTestsArgs);

        m.put("get_jpa_model", objectMapper.createObjectNode());
        m.put("get_http_endpoints", objectMapper.createObjectNode());
        m.put("get_dependency_graph", objectMapper.createObjectNode());
        m.put("get_classpath_info", objectMapper.createObjectNode());
        m.put("get_di_registrations", objectMapper.createObjectNode());
        m.put("get_project_structure", objectMapper.createObjectNode());

        // change_method_signature.
        ObjectNode csArgs = objectMapper.createObjectNode();
        csArgs.put("filePath", refTarget);
        csArgs.put("line", 71);
        csArgs.put("column", 18);
        ArrayNode params = csArgs.putArray("newParameters");
        params.addObject().put("type", "String").put("name", "message");
        params.addObject().put("type", "int").put("name", "count");
        m.put("change_method_signature", csArgs);

        // apply_quick_fix: a documented fix on BugPatterns (best-effort).
        ObjectNode applyArgs = objectMapper.createObjectNode();
        applyArgs.put("filePath", bugPatterns);
        applyArgs.put("line", 0);
        applyArgs.put("column", 0);
        applyArgs.put("fixId", "remove_import");
        m.put("apply_quick_fix", applyArgs);

        // apply_cleanup: a headless JDT clean-up over a whole file.
        ObjectNode cleanupArgs = objectMapper.createObjectNode();
        cleanupArgs.put("filePath", calcPath);
        cleanupArgs.put("cleanupId", "convert_loops");
        m.put("apply_cleanup", cleanupArgs);

        // encapsulate_field: RefactoringTarget.userName (0-based line 15, col 19).
        ObjectNode encapsulateArgs = objectMapper.createObjectNode();
        encapsulateArgs.put("filePath", refTarget);
        encapsulateArgs.put("line", 15);
        encapsulateArgs.put("column", 19);
        m.put("encapsulate_field", encapsulateArgs);

        // pull_up / push_down: any member position; a refusal (no project
        // superclass) is still a well-formed response for the parity contract.
        ObjectNode pullUpArgs = objectMapper.createObjectNode();
        pullUpArgs.put("filePath", calcPath);
        pullUpArgs.put("line", 14);
        pullUpArgs.put("column", 15);
        m.put("pull_up", pullUpArgs);

        ObjectNode pushDownArgs = objectMapper.createObjectNode();
        pushDownArgs.put("filePath", calcPath);
        pushDownArgs.put("line", 14);
        pushDownArgs.put("column", 15);
        m.put("push_down", pushDownArgs);

        ObjectNode extractSuperArgs = objectMapper.createObjectNode();
        extractSuperArgs.put("filePath", calcPath);
        extractSuperArgs.put("line", 14);
        extractSuperArgs.put("column", 15);
        extractSuperArgs.put("superclassName", "CalculatorBase");
        m.put("extract_superclass", extractSuperArgs);

        ObjectNode ipoArgs = objectMapper.createObjectNode();
        ipoArgs.put("filePath", calcPath);
        ipoArgs.put("line", 14);
        ipoArgs.put("column", 15);
        m.put("introduce_parameter_object", ipoArgs);

        // move_type_to_new_file: a top-level position is refused with
        // INVALID_PARAMETER - a well-formed response for the parity contract.
        ObjectNode moveTypeArgs = objectMapper.createObjectNode();
        moveTypeArgs.put("filePath", calcPath);
        moveTypeArgs.put("line", 14);
        moveTypeArgs.put("column", 15);
        m.put("move_type_to_new_file", moveTypeArgs);

        ObjectNode diagnoseFixArgs = objectMapper.createObjectNode();
        diagnoseFixArgs.put("filePath", calcPath);
        m.put("diagnose_and_fix", diagnoseFixArgs);

        // Project lifecycle.
        m.put("health_check", objectMapper.createObjectNode());
        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", projectPath.toString());
        m.put("load_project", loadArgs);

        return m;
    }
}

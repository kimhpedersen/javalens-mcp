package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeDataFlowToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeDataFlowTool tool;
    private ObjectMapper objectMapper;
    private String patternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeDataFlowTool(() -> service);
        objectMapper = new ObjectMapper();
        patternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DiAndReflectionPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Data Flow Detection Tests ==========

    @Test
    @DisplayName("should analyze data flow of a method with reads and writes")
    void analyzesDataFlow() {
        // dataFlowExample method has variables read, written, and returned
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);  // dataFlowExample method (zero-based: line 77 in editor)
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("dataFlowExample", data.get("method"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) data.get("variables");
        assertFalse(variables.isEmpty(), "Should detect variables");

        // Verify variable structure — exact-content checks
        Map<String, Object> firstVar = variables.get(0);
        String name = (String) firstVar.get("name");
        assertNotNull(name, "name missing: " + firstVar);
        assertFalse(name.isBlank(), "name non-blank: " + firstVar);
        String type = (String) firstVar.get("type");
        assertNotNull(type, "type missing: " + firstVar);
        assertFalse(type.isBlank(), "type non-blank: " + firstVar);
        String kind = (String) firstVar.get("kind");
        assertNotNull(kind, "kind missing: " + firstVar);
        assertTrue(List.of("parameter", "local", "field").contains(kind),
            "kind in {parameter,local,field}; got: " + firstVar);
        assertTrue(firstVar.get("read") instanceof Boolean,
            "read must be Boolean; got: " + firstVar);
        assertTrue(firstVar.get("written") instanceof Boolean,
            "written must be Boolean; got: " + firstVar);
    }

    @Test
    @DisplayName("should detect parameter as parameter kind")
    void detectsParameterKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) getData(response).get("variables");

        boolean foundParameter = variables.stream()
            .anyMatch(v -> "input".equals(v.get("name")) && "parameter".equals(v.get("kind")));
        assertTrue(foundParameter, "Should detect 'input' as a parameter. Variables found: " + variables);
    }

    @Test
    @DisplayName("should detect variables that are both read and written")
    void detectsReadAndWritten() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) getData(response).get("variables");

        // x is assigned from input, then reassigned, then read
        boolean foundReadWrite = variables.stream()
            .anyMatch(v -> "x".equals(v.get("name")) && (boolean) v.get("read") && (boolean) v.get("written"));
        assertTrue(foundReadWrite, "Should detect variable 'x' as both read and written. Variables found: " + variables);
    }

    @Test
    @DisplayName("should count return statements")
    void countsReturnStatements() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int returnCount = (int) data.get("returnStatements");
        assertTrue(returnCount > 0, "Should detect return statements");
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("dataFlowExample: returnStatements == 1 (exact)")
    void dataFlowExample_exactReturnCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Method body holds exactly one `return z;` statement.
        assertEquals(1, ((Number) getData(r).get("returnStatements")).intValue(),
            "dataFlowExample has exactly 1 return; got: " + getData(r).get("returnStatements"));
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("should return error when filePath is missing")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("should return error for non-method position")
    void returnsErrorForNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> varsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("variables");
    }

    @Test
    @DisplayName("parameterCount reported: dataFlowExample(int input) → 1")
    void parameterCount_reported() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, ((Number) getData(r).get("parameterCount")).intValue(),
            "dataFlowExample has 1 parameter `input`; got: " + getData(r).get("parameterCount"));
    }

    @Test
    @DisplayName("Each variable entry includes name, type, kind, declared, read, written, readCount, writeCount")
    void variableEntry_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> v : varsOf(r)) {
            for (String key : List.of("name", "type", "kind", "declared", "read", "written", "readCount", "writeCount")) {
                assertNotNull(v.get(key), key + " missing on variable: " + v);
            }
        }
    }

    @Test
    @DisplayName("Local variable y is declared, read+written")
    void localVariable_y_readWrite() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> y = varsOf(r).stream()
            .filter(v -> "y".equals(v.get("name"))).findFirst().orElseThrow();
        assertEquals("local", y.get("kind"));
        assertEquals(Boolean.TRUE, y.get("declared"));
        // y has initializer (=0) which counts as a write, and y is used in z = x + y.
        assertTrue(((Number) y.get("writeCount")).intValue() >= 1);
        assertTrue(((Number) y.get("readCount")).intValue() >= 1);
    }

    @Test
    @DisplayName("Local variable z is declared and written, used in return")
    void localVariable_z_writeOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> z = varsOf(r).stream()
            .filter(v -> "z".equals(v.get("name"))).findFirst().orElseThrow();
        assertEquals("local", z.get("kind"));
        assertEquals(Boolean.TRUE, z.get("declared"));
        assertTrue(((Number) z.get("writeCount")).intValue() >= 2,
            "z is written twice (initial assignment then conditional `z = z + input`); got writes=" + z.get("writeCount"));
    }

    @Test
    @DisplayName("Variables list includes input (parameter) and locals x, y, z")
    void variables_setIncludesAllExpected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Map<String, Object> v : varsOf(r)) names.add((String) v.get("name"));
        for (String expected : List.of("input", "x", "y", "z")) {
            assertTrue(names.contains(expected),
                "Variables must include " + expected + "; got: " + names);
        }
    }

    @Test
    @DisplayName("Field write via `this.field = value` (FieldAccess on LHS) is counted as a write, not a read")
    @SuppressWarnings("unchecked")
    void thisQualifiedAssignment_countedAsWrite() {
        // DataFlowFieldAccess.writeViaThisQualifier has:
        //     this.counter = 42;
        //     this.label = "initialized";
        // The SimpleName `counter` has parent FieldAccess, whose parent is Assignment.
        // The current visitor only checks the IMMEDIATE parent; since `counter`'s
        // immediate parent is FieldAccess (not Assignment), the assignment-LHS check
        // doesn't fire and the visitor falls through to "else → readCount++". That
        // misclassifies a write as a read.
        String dfPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DataFlowFieldAccess.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", dfPath);
        // 0-based line 13: `    public void writeViaThisQualifier() {`
        args.put("line", 13);
        args.put("column", 17);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_data_flow on writeViaThisQualifier must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<Map<String, Object>> vars = (List<Map<String, Object>>) getData(r).get("variables");
        Map<String, Object> counter = vars.stream()
            .filter(v -> "counter".equals(v.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "field `counter` must appear among tracked variables; got: " + vars));
        int writeCount = ((Number) counter.get("writeCount")).intValue();
        assertTrue(writeCount >= 1,
            "`this.counter = 42` is a write to counter — writeCount must be >= 1; " +
                "got: " + counter);
        assertEquals(Boolean.TRUE, counter.get("written"),
            "`written` boolean must reflect the write; got: " + counter);
    }

    @Test
    @DisplayName("Compound assignment via `this.field += value` counts as BOTH a write and a read")
    @SuppressWarnings("unchecked")
    void thisQualifiedCompoundAssignment_countedAsReadAndWrite() {
        // DataFlowFieldAccess.compoundAssignViaThis has `this.counter += 5;`.
        // Compound assignment (+=, -=, *=, etc.) reads the old value, combines
        // with the RHS, and writes back. Data-flow classification must count it
        // as BOTH a read and a write. The current visitor counts compound under
        // the same branch as `=` (assignment LHS) — which only increments writeCount,
        // missing the read.
        String dfPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DataFlowFieldAccess.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", dfPath);
        // 0-based line 18: `    public void compoundAssignViaThis() {`
        args.put("line", 18);
        args.put("column", 17);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> vars = (List<Map<String, Object>>) getData(r).get("variables");
        Map<String, Object> counter = vars.stream()
            .filter(v -> "counter".equals(v.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "field `counter` must appear; got: " + vars));
        assertTrue(((Number) counter.get("writeCount")).intValue() >= 1,
            "Compound assignment is a write — writeCount must be >= 1; got: " + counter);
        assertTrue(((Number) counter.get("readCount")).intValue() >= 1,
            "Compound assignment is also a read — readCount must be >= 1; got: " + counter);
    }
}

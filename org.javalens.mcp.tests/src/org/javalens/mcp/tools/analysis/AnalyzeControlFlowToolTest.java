package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeControlFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeControlFlowToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeControlFlowTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String patternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeControlFlowTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        patternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DiAndReflectionPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Control Flow Detection Tests ==========

    @Test
    @DisplayName("controlFlowExample: exact branch/loop/return/throw/try/nesting profile")
    void analyzesControlFlow() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 42);  // controlFlowExample (0-based); col 18 = method name
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("controlFlowExample", data.get("method"));
        // 4 if-conditions (value<0, value==0, flag, i%2==0); no switch/ternary.
        assertEquals(4, ((Number) data.get("branches")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> loops = (Map<String, Object>) data.get("loops");
        assertEquals(2, ((Number) loops.get("total")).intValue());
        assertEquals(1, ((Number) loops.get("for")).intValue());
        assertEquals(1, ((Number) loops.get("while")).intValue());
        assertEquals(0, ((Number) loops.get("enhancedFor")).intValue());
        assertEquals(0, ((Number) loops.get("doWhile")).intValue());

        // return "zero" (0-based line 48), return result (0-based line 71).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> returnPoints = (List<Map<String, Object>>) data.get("returnPoints");
        assertEquals(List.of(48, 71),
            returnPoints.stream().map(p -> ((Number) p.get("line")).intValue()).toList());

        // single throw of IllegalArgumentException at 0-based line 44.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> throwPoints = (List<Map<String, Object>>) data.get("throwPoints");
        assertEquals(1, throwPoints.size());
        assertEquals(44, ((Number) throwPoints.get(0).get("line")).intValue());
        assertEquals("new IllegalArgumentException(\"Negative value\")",
            throwPoints.get(0).get("expression"));

        assertEquals(0, ((Number) data.get("breakStatements")).intValue());
        assertEquals(0, ((Number) data.get("continueStatements")).intValue());
        // if(flag) -> for -> if(i%2==0) is the deepest chain.
        assertEquals(3, ((Number) data.get("maxNestingDepth")).intValue());
    }

    @Test
    @DisplayName("controlFlowExample try-catch: exact line, caughtTypes [Exception], no finally")
    void detectsTryCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 42);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tryCatchBlocks = (List<Map<String, Object>>) getData(response).get("tryCatchBlocks");
        assertEquals(1, tryCatchBlocks.size());
        Map<String, Object> block = tryCatchBlocks.get(0);
        // try at 0-based line 60, catch (Exception e), no finally clause.
        assertEquals(60, ((Number) block.get("line")).intValue());
        assertEquals(List.of("Exception"), block.get("caughtTypes"));
        assertEquals(false, block.get("hasFinally"));
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("missing filePath is an exact INVALID_PARAMETER error")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required parameter missing",
            response.getError().getMessage());
    }

    @Test
    @DisplayName("non-method position (package declaration) -> exact SYMBOL_NOT_FOUND")
    void returnsErrorForNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 0);  // package declaration — not in a method
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND, response.getError().getCode());
        assertEquals("Symbol not found: No method found at " + patternsPath + ":0:0",
            response.getError().getMessage());
    }

    // ========== Semantic-grade tests (ControlFlowPatterns fixture) ==========

    @Test
    @DisplayName("ControlFlowPatterns.multipleReturns has exactly 4 returns and 0 throws")
    void multipleReturns_exactCounts() {
        String cfp = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ControlFlowPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", cfp);
        // multipleReturns method declaration at 1-based line 92 → 0-based line 91.
        // Position on method name at column 15.
        args.put("line", 91);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("multipleReturns", data.get("method"));
        @SuppressWarnings("unchecked")
        List<?> returns = (List<?>) data.get("returnPoints");
        assertEquals(4, returns.size(),
            "multipleReturns has 4 return statements; got: " + returns);
        @SuppressWarnings("unchecked")
        List<?> throwsP = (List<?>) data.get("throwPoints");
        assertEquals(0, throwsP.size(),
            "multipleReturns has no throws; got: " + throwsP);
    }

    @Test
    @DisplayName("ControlFlowPatterns.throwMultiple has 3 throw points")
    void throwMultiple_exactCount() {
        String cfp = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ControlFlowPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", cfp);
        // throwMultiple method declaration at 1-based line 105 → 0-based line 104
        args.put("line", 104);
        args.put("column", 16);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> throwsP = (List<?>) getData(r).get("throwPoints");
        assertEquals(3, throwsP.size(),
            "throwMultiple has 3 throw statements; got: " + throwsP);
    }

    // ========== Behavior-matrix coverage ==========

    private String cfp() {
        return helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ControlFlowPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loopsOf(Map<String, Object> data) {
        return (Map<String, Object>) data.get("loops");
    }

    private ObjectNode methodArgs(String path, int line, int col) {
        ObjectNode a = objectMapper.createObjectNode();
        a.put("filePath", path);
        a.put("line", line);
        a.put("column", col);
        return a;
    }

    @Test
    @DisplayName("simpleLinear: zero branches, zero loops, exactly 1 return, 0 throws, 0 nesting")
    void simpleLinear_allCountsZeroExceptOneReturn() {
        // 1-based line 10 → 0-based 9
        ToolResponse r = tool.execute(methodArgs(cfp(), 9, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("simpleLinear", data.get("method"));
        assertEquals(0, ((Number) data.get("branches")).intValue());
        assertEquals(0, ((Number) loopsOf(data).get("total")).intValue());
        assertEquals(1, ((List<?>) data.get("returnPoints")).size());
        assertEquals(0, ((List<?>) data.get("throwPoints")).size());
        assertEquals(0, ((Number) data.get("maxNestingDepth")).intValue());
    }

    @Test
    @DisplayName("forLoop: loops.for=1, loops.total=1, other loop counts 0")
    void forLoop_loopCountByKind() {
        // 1-based line 48 → 0-based 47
        ToolResponse r = tool.execute(methodArgs(cfp(), 47, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> loops = loopsOf(getData(r));
        assertEquals(1, ((Number) loops.get("total")).intValue());
        assertEquals(1, ((Number) loops.get("for")).intValue());
        assertEquals(0, ((Number) loops.get("enhancedFor")).intValue());
        assertEquals(0, ((Number) loops.get("while")).intValue());
        assertEquals(0, ((Number) loops.get("doWhile")).intValue());
    }

    @Test
    @DisplayName("whileLoop: loops.while=1, loops.total=1")
    void whileLoop_loopCountByKind() {
        ToolResponse r = tool.execute(methodArgs(cfp(), 55, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> loops = loopsOf(getData(r));
        assertEquals(1, ((Number) loops.get("while")).intValue());
        assertEquals(1, ((Number) loops.get("total")).intValue());
        assertEquals(0, ((Number) loops.get("for")).intValue());
    }

    @Test
    @DisplayName("doWhileLoop: loops.doWhile=1, loops.total=1")
    void doWhileLoop_loopCountByKind() {
        ToolResponse r = tool.execute(methodArgs(cfp(), 65, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> loops = loopsOf(getData(r));
        assertEquals(1, ((Number) loops.get("doWhile")).intValue());
        assertEquals(1, ((Number) loops.get("total")).intValue());
    }

    @Test
    @DisplayName("enhancedForCollection: loops.enhancedFor=1")
    void enhancedForCollection_loopCountByKind() {
        ToolResponse r = tool.execute(methodArgs(cfp(), 75, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> loops = loopsOf(getData(r));
        assertEquals(1, ((Number) loops.get("enhancedFor")).intValue());
        assertEquals(1, ((Number) loops.get("total")).intValue());
    }

    @Test
    @DisplayName("ternary: exactly 1 branch (ConditionalExpression), 1 return, 0 nesting")
    void ternary_isBranch() {
        // 1-based line 26 → 0-based 25
        ToolResponse r = tool.execute(methodArgs(cfp(), 25, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("ternary", data.get("method"));
        assertEquals(1, ((Number) data.get("branches")).intValue(),
            "`x >= 0 ? x : -x` is exactly one ConditionalExpression branch");
        assertEquals(1, ((List<?>) data.get("returnPoints")).size());
        assertEquals(0, ((Number) data.get("maxNestingDepth")).intValue(),
            "a ternary does not increase nesting depth");
    }

    @Test
    @DisplayName("multiCatch tryCatchBlock has caughtTypes covering both branches of `A | B`")
    void multiCatch_caughtTypes() {
        // 1-based line 119 → 0-based 118
        ToolResponse r = tool.execute(methodArgs(cfp(), 118, 16));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) getData(r).get("tryCatchBlocks");
        assertEquals(1, blocks.size(), "multiCatch has exactly one try-catch block");
        @SuppressWarnings("unchecked")
        List<String> caught = (List<String>) blocks.get(0).get("caughtTypes");
        // JDT renders `A | B` as a single UnionType.toString() entry joined with "|" (no spaces).
        assertEquals(List.of("NumberFormatException|IOException"), caught);
    }

    @Test
    @DisplayName("nestedTry: 2 tryCatchBlocks; maxNestingDepth exactly 2")
    void nestedTry_blockCountAndDepth() {
        // 1-based line 129 → 0-based 128
        ToolResponse r = tool.execute(methodArgs(cfp(), 128, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<?> blocks = (List<?>) data.get("tryCatchBlocks");
        assertEquals(2, blocks.size(), "nestedTry has two try-catch blocks");
        assertEquals(2, ((Number) data.get("maxNestingDepth")).intValue(),
            "outer try -> inner try is exactly depth 2");
    }

    @Test
    @DisplayName("deeplyNested: maxNestingDepth exactly 4 (if -> for -> if -> while)")
    void deeplyNested_depth() {
        // 1-based line 147 → 0-based 146
        ToolResponse r = tool.execute(methodArgs(cfp(), 146, 16));
        assertTrue(r.isSuccess());
        assertEquals(4, ((Number) getData(r).get("maxNestingDepth")).intValue(),
            "if -> for -> if -> while is exactly depth 4");
    }

    @Test
    @DisplayName("switch expression branches: Java21Modern.describe has 5 cases (including default) → branches == 4 non-default cases")
    void switchExpression_branchesCountedPerCase() {
        // Java21Modern.describe has a switch expression with 5 cases:
        //   case null    -> ...
        //   case String s -> ...
        //   case Integer i -> ...
        //   case int[] arr -> ...
        //   default     -> ...
        // The contract counts each non-default SwitchCase as one branch. The visitor's
        // visit(SwitchCase) fires for switch expressions too (cases are direct children
        // regardless of whether they are inside a statement or expression form).
        String j21Path = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Java21Modern.java").toString();
        // 0-based line 39: `    public String describe(Object obj) {`
        // "describe" identifier starts at column 18 (4 indent + "public String " = 18).
        ObjectNode args = methodArgs(j21Path, 39, 18);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_control_flow on Java21Modern.describe must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("describe", data.get("method"));
        assertEquals(4, ((Number) data.get("branches")).intValue(),
            "describe has 4 non-default switch-expression cases (null, String, Integer, int[]); " +
                "default is excluded by source rule; got: " + data.get("branches"));
        // The method's `return switch(...)` is a single ReturnStatement.
        assertEquals(1, ((List<?>) data.get("returnPoints")).size(),
            "describe has exactly one return statement; got: " + data.get("returnPoints"));
    }

    @Test
    @DisplayName("guarded switch patterns: Java21Modern.classify with `when` clauses produces a branch per case")
    void guardedSwitchPatterns_branchesCountedPerCase() {
        // Java21Modern.classify has 4 cases (3 Integer-with-when + null/default fused).
        // The merged case `case null, default` is treated as default by SwitchCase.isDefault()
        // (the AST flag is set when at least one expression of the case is `default`).
        // So we expect 3 non-default branches.
        String j21Path = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Java21Modern.java").toString();
        // 0-based line 58: `    public String classify(Object o) {`
        // "classify" at column 18.
        ObjectNode args = methodArgs(j21Path, 58, 18);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_control_flow on Java21Modern.classify must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("classify", data.get("method"));
        // All 4 case labels count as branches: the three `Integer ... when`/plain-Integer
        // cases plus the merged `case null, default` label, whose SwitchCase.isDefault()
        // is false (the default flag is not set on a fused null+default label in this JDT),
        // so it is NOT excluded.
        assertEquals(4, ((Number) data.get("branches")).intValue(),
            "classify has 4 non-default switch-expression case labels; got: " + data.get("branches"));
        assertEquals(1, ((List<?>) data.get("returnPoints")).size(),
            "classify is a single `return switch(...)`; got: " + data.get("returnPoints"));
    }

    // ========== MCP envelope seam (real registerTools() wiring through processMessage) ==========

    @Test
    @DisplayName("Through the real registerTools() wiring: simpleLinear reports the exact all-zero-except-one-return profile")
    void envelope_simpleLinear_exactProfile() {
        ObjectNode args = envelope.args();
        args.put("filePath", cfp());
        args.put("line", 9);    // 0-based: simpleLinear
        args.put("column", 16);
        JsonNode payload = envelope.assertEnvelopeFidelity("analyze_control_flow", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "analyze_control_flow failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("simpleLinear", data.get("method").asText());
        assertEquals(0, data.get("branches").asInt(), "no branches through the envelope");
        assertEquals(0, data.get("loops").get("total").asInt(), "no loops through the envelope");
        assertEquals(1, data.get("returnPoints").size(), "exactly one return through the envelope");
        assertEquals(0, data.get("throwPoints").size(), "no throws through the envelope");
        assertEquals(0, data.get("maxNestingDepth").asInt(), "zero nesting through the envelope");
    }
}

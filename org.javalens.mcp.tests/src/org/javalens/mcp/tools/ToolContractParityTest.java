package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.fixtures.TestRegistryBuilder;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinned cross-tool contract parity. Iterates every tool that
 * {@link JavaLensApplication#registerTools} registers and asserts:
 *
 * <ul>
 *   <li><b>Static invariants</b> on name / description / schema shape</li>
 *   <li><b>Dispatch invariants</b>: execute({}) with no service returns a documented
 *       error code; never throws or returns null or inappropriate success</li>
 *   <li><b>Documented USAGE convention</b> coverage across descriptions</li>
 *   <li><b>Behavioral parity</b>: every tool can be invoked with a known-valid input
 *       against simple-maven and either succeeds with the expected primary fields
 *       present OR returns a documented error code</li>
 * </ul>
 */
class ToolContractParityTest {

    @RegisterExtension
    static TestProjectHelper helper = new TestProjectHelper();

    private static TestRegistryBuilder.Built built;
    private static TestRegistryBuilder.Built builtWithService;
    private static ToolRegistry registry;
    private static ToolRegistry registryWithService;
    private static JdtServiceImpl loadedService;
    private static Path projectPath;
    private static ObjectMapper objectMapper;

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Set<String> ACCEPTABLE_ERROR_CODES = Set.of(
        "PROJECT_NOT_LOADED",
        "PROJECT_LOADING",
        "PROJECT_LOAD_FAILED",
        "INVALID_PARAMETER",
        "SYMBOL_NOT_FOUND",
        "INTERNAL_ERROR"
    );

    @BeforeAll
    static void setUpRegistry() throws Exception {
        // Service-less registry — every tool's session is attached to nothing.
        built = TestRegistryBuilder.build(null);
        registry = built.registry();

        // Service-backed registry: load simple-maven, then build a new registry
        // whose bound session is attached to the loaded service.
        helper.beforeEach(null);
        loadedService = helper.loadProject("simple-maven");
        projectPath = helper.getFixturePath("simple-maven");

        builtWithService = TestRegistryBuilder.build(loadedService);
        registryWithService = builtWithService.registry();

        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("registerTools populates more than one tool")
    void registry_isPopulated() {
        assertTrue(registry.getToolCount() > 1,
            "Expected multiple registered tools; got: " + registry.getToolCount());
    }

    @Test
    @DisplayName("Every registered tool's name matches lower_snake_case")
    void everyTool_nameIsLowerSnakeCase() {
        Set<String> bad = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            if (!TOOL_NAME_PATTERN.matcher(name).matches()) {
                bad.add(name);
            }
        }
        assertTrue(bad.isEmpty(),
            "Tool names must be lower_snake_case; offenders: " + bad);
    }

    @Test
    @DisplayName("Every tool's description follows the USAGE: + OUTPUT: convention")
    void everyTool_descriptionFollowsUsageOutputConvention() {
        Set<String> missingUsage = new TreeSet<>();
        Set<String> missingOutput = new TreeSet<>();
        Set<String> blank = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            String desc = tool.getDescription();
            if (desc == null || desc.isBlank()) {
                blank.add(name);
                continue;
            }
            if (!desc.contains("USAGE:")) missingUsage.add(name);
            if (!desc.contains("OUTPUT:")) missingOutput.add(name);
        }
        assertTrue(blank.isEmpty(), "Tools with blank descriptions: " + blank);
        assertTrue(missingUsage.isEmpty(), "Tools missing USAGE: " + missingUsage);
        assertTrue(missingOutput.isEmpty(), "Tools missing OUTPUT: " + missingOutput);
    }

    @Test
    @DisplayName("Every tool exposes a non-null, non-empty input schema")
    void everyTool_inputSchemaIsPresentAndNonEmpty() {
        Set<String> bad = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            Map<String, Object> schema = tool.getInputSchema();
            if (schema == null || schema.isEmpty()) bad.add(name);
        }
        assertTrue(bad.isEmpty(), "Tools with null/empty input schema: " + bad);
    }

    @Test
    @DisplayName("getName() returns the same string as the registry's key for every tool")
    void everyTool_nameMatchesRegistryKey() {
        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            assertEquals(name, tool.getName(),
                "Registry key must match getName(); registry-key=" + name
                    + " tool.getName()=" + tool.getName());
        }
    }

    @Test
    @DisplayName("Every tool's execute() returns a well-formed response with no service loaded")
    void everyTool_executeWithoutService_returnsErrorResponse() {
        built.bind();
        Set<String> threw = new TreeSet<>();
        Set<String> nullResp = new TreeSet<>();
        Set<String> successWithoutService = new TreeSet<>();
        Set<String> blankErrorCode = new TreeSet<>();
        Set<String> unknownErrorCode = new TreeSet<>();

        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            ToolResponse resp;
            try {
                resp = tool.execute(objectMapper.createObjectNode());
            } catch (Throwable t) {
                threw.add(name + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                continue;
            }
            if (resp == null) {
                nullResp.add(name);
                continue;
            }
            if (resp.isSuccess()) {
                if (!isServiceIndependent(name)) successWithoutService.add(name);
                continue;
            }
            if (resp.getError() == null
                || resp.getError().getCode() == null
                || resp.getError().getCode().isBlank()) {
                blankErrorCode.add(name);
                continue;
            }
            String code = resp.getError().getCode();
            if (!ACCEPTABLE_ERROR_CODES.contains(code)) {
                unknownErrorCode.add(name + " (" + code + ")");
            }
        }

        assertTrue(threw.isEmpty(), "Tools that threw on execute({}): " + threw);
        assertTrue(nullResp.isEmpty(), "Tools that returned null response: " + nullResp);
        assertTrue(successWithoutService.isEmpty(),
            "Service-dependent tools must not succeed without a service: " + successWithoutService);
        assertTrue(blankErrorCode.isEmpty(), "Tools with blank error code: " + blankErrorCode);
        assertTrue(unknownErrorCode.isEmpty(),
            "Tools with error codes outside the documented set " + ACCEPTABLE_ERROR_CODES
                + ": " + unknownErrorCode);
    }

    @Test
    @DisplayName("Every tool can be invoked against simple-maven with a known-valid input and produces a well-formed response")
    void everyTool_validInvocation_producesWellFormedResponse() {
        builtWithService.bind();
        Map<String, ObjectNode> inputs = buildValidInputs();
        Set<String> notInRegistry = new TreeSet<>(inputs.keySet());
        for (String name : registryWithService.getToolNames()) notInRegistry.remove(name);
        assertTrue(notInRegistry.isEmpty(),
            "Test-side input map references tools no longer registered: " + notInRegistry);

        Set<String> missingInputs = new TreeSet<>();
        Map<String, String> badResponses = new TreeMap<>();

        for (String name : registryWithService.getToolNames()) {
            if (!inputs.containsKey(name)) {
                missingInputs.add(name);
                continue;
            }
            Tool tool = registryWithService.getTool(name).orElseThrow();
            ObjectNode args = inputs.get(name);
            ToolResponse resp;
            try {
                resp = tool.execute(args);
            } catch (Throwable t) {
                badResponses.put(name, "THREW: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                continue;
            }
            if (resp == null) {
                badResponses.put(name, "NULL response");
                continue;
            }
            if (resp.isSuccess()) {
                if (resp.getData() == null) {
                    badResponses.put(name, "SUCCESS with null data");
                }
                // success path is acceptable
                continue;
            }
            // Error path is acceptable IF the code is in the documented set — many tools
            // legitimately error on minimal inputs (e.g., a position that doesn't resolve).
            if (resp.getError() == null
                || resp.getError().getCode() == null
                || resp.getError().getCode().isBlank()) {
                badResponses.put(name, "Error response with blank code");
                continue;
            }
            String code = resp.getError().getCode();
            if (!ACCEPTABLE_ERROR_CODES.contains(code)) {
                badResponses.put(name, "Unknown error code: " + code);
            }
        }

        assertTrue(missingInputs.isEmpty(),
            "Every registered tool needs a known-valid input in the test's inputs map; "
                + "missing: " + missingInputs);
        assertTrue(badResponses.isEmpty(),
            "Tools that produced ill-formed responses on a known-valid input: " + badResponses);
    }

    @Test
    @DisplayName("Tools that document specific output concepts in OUTPUT: line actually emit those concepts on success")
    void everyTool_documentedOutputConceptsAppear() {
        builtWithService.bind();
        // Concept-to-field map: words/phrases that may appear in a tool's OUTPUT: line,
        // mapped to the field name(s) we expect in a successful response. This is the
        // doc/impl parity check the plan calls for. The map is intentionally specific —
        // a fuzzy "any keyword anywhere" heuristic would have too many false positives.
        Map<String, Set<String>> conceptToFields = buildConceptFieldMap();

        Map<String, ObjectNode> inputs = buildValidInputs();
        Map<String, String> failures = new TreeMap<>();

        for (String name : registryWithService.getToolNames()) {
            Tool tool = registryWithService.getTool(name).orElseThrow();
            ObjectNode args = inputs.get(name);
            if (args == null) continue;
            ToolResponse resp;
            try {
                resp = tool.execute(args);
            } catch (Throwable t) {
                continue; // covered by previous test
            }
            if (resp == null || !resp.isSuccess() || resp.getData() == null) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getData();

            String desc = tool.getDescription();
            String outputLine = extractOutputLine(desc);
            if (outputLine == null) continue;

            for (Map.Entry<String, Set<String>> entry : conceptToFields.entrySet()) {
                String concept = entry.getKey();
                Set<String> candidateFields = entry.getValue();
                if (!outputLine.toLowerCase().contains(concept.toLowerCase())) continue;
                boolean present = candidateFields.stream().anyMatch(data::containsKey);
                if (!present) {
                    failures.put(name + "[" + concept + "]",
                        "OUTPUT mentions '" + concept + "' but response has none of "
                            + candidateFields + "; response keys=" + data.keySet());
                }
            }
        }

        assertTrue(failures.isEmpty(),
            "Doc/impl parity drift — OUTPUT line claims output that the response does not emit: "
                + failures);
    }

    private static String extractOutputLine(String description) {
        if (description == null) return null;
        Matcher m = Pattern.compile("OUTPUT:([^\n]+)").matcher(description);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    /**
     * Map of OUTPUT-line concept words to the response field names that satisfy them.
     * Curated narrowly: each entry is a specific (concept-keyword → required-field-name)
     * pair where the OUTPUT line says X and the response MUST have a top-level field
     * named X (or a documented synonym). OUTPUT lines that describe nested structures
     * (e.g. "List of X locations" where X is the field name and locations are properties
     * of each element) are intentionally not enforced here — that's per-tool snapshot
     * territory, not data-driven assertion territory. The pairs below catch real drift
     * (a tool that removes its `diagnostics` field while keeping the description that
     * says it emits diagnostics) without false-positiving on natural-language phrasing.
     */
    private static Map<String, Set<String>> buildConceptFieldMap() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("circular dependency", Set.of("cycles"));
        m.put("circular", Set.of("cycles"));
        m.put("classpath", Set.of("classpath", "sourceFolders", "libraries"));
        m.put("implementations", Set.of("implementations"));
        m.put("imports", Set.of("imports", "currentImports", "unusedImports", "organizedImportBlock"));
        m.put("text edits", Set.of("editsByFile", "textEdit", "edits", "classEdits"));
        return m;
    }

    /**
     * Per-tool known-valid invocation arguments against simple-maven. Calculator.java
     * line 14 / column 15 is the `add` method declaration; line 6 / column 16 is the
     * `lastResult` field. RefactoringTarget.java line 71 / column 18 is `formatMessage`.
     * Tools that need a typeName are passed "com.example.Calculator".
     */
    private Map<String, ObjectNode> buildValidInputs() {
        // Single source of truth shared with ProtocolParityTest.
        return ToolInvocationInputs.buildValidInputs(objectMapper, projectPath);
    }

    /** Tools that legitimately operate without a loaded project. */
    private static boolean isServiceIndependent(String name) {
        return "health_check".equals(name) || "load_project".equals(name);
    }

    @Test
    @DisplayName("Every tool's name has reasonable shape (2 to 60 chars)")
    void everyTool_nameHasReasonableShape() {
        Set<String> bad = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            if (name.length() < 2 || name.length() > 60) {
                bad.add(name + " (len=" + name.length() + ")");
            }
        }
        assertTrue(bad.isEmpty(), "Tools with unreasonable name length: " + bad);
    }
}

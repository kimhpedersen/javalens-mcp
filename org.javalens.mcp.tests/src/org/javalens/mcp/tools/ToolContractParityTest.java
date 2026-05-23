package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinned cross-tool contract parity. Iterates every tool that
 * {@link JavaLensApplication#registerTools} registers and asserts uniform
 * invariants:
 *
 * <ul>
 *   <li>name is non-blank and follows {@code lower_snake_case}</li>
 *   <li>description is non-blank and mentions {@code USAGE:} (the documented
 *       convention for tool descriptions)</li>
 *   <li>input schema is a non-null, non-empty Map</li>
 *   <li>execute() with no-service routes to a well-formed error response
 *       (never a thrown exception); the error code is one of the documented set</li>
 * </ul>
 *
 * <p>Catches doc/impl drift the per-tool tests miss — for example a tool that
 * silently throws NullPointerException when service is null, or a tool that
 * registers with a malformed name.
 */
class ToolContractParityTest {

    private static ToolRegistry registry;
    private static ObjectMapper objectMapper;

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Set<String> ACCEPTABLE_ERROR_CODES = Set.of(
        "PROJECT_NOT_LOADED",
        "PROJECT_LOADING",
        "PROJECT_LOAD_FAILED",
        "INVALID_PARAMETER",
        "SYMBOL_NOT_FOUND"
    );

    @BeforeAll
    static void setUpRegistry() throws Exception {
        JavaLensApplication app = new JavaLensApplication();
        // toolRegistry is private; initialize it via reflection so registerTools has
        // somewhere to register into. registerTools is also private; invoke directly.
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        ToolRegistry r = new ToolRegistry();
        registryField.set(app, r);

        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);

        registry = r;
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
    @DisplayName("Every tool has a non-blank description that mentions USAGE: convention")
    void everyTool_descriptionMentionsUsage() {
        Set<String> missingUsage = new TreeSet<>();
        Set<String> blank = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            String desc = tool.getDescription();
            if (desc == null || desc.isBlank()) {
                blank.add(name);
                continue;
            }
            if (!desc.contains("USAGE:")) {
                missingUsage.add(name);
            }
        }
        assertTrue(blank.isEmpty(), "Tools with blank descriptions: " + blank);
        assertTrue(missingUsage.isEmpty(),
            "Tools missing USAGE: in description (documented convention): " + missingUsage);
    }

    @Test
    @DisplayName("Every tool exposes a non-null, non-empty input schema")
    void everyTool_inputSchemaIsPresentAndNonEmpty() {
        Set<String> bad = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            Tool tool = registry.getTool(name).orElseThrow();
            Map<String, Object> schema = tool.getInputSchema();
            if (schema == null || schema.isEmpty()) {
                bad.add(name);
            }
        }
        assertTrue(bad.isEmpty(),
            "Tools with null/empty input schema: " + bad);
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
        // The registry was populated via the JavaLensApplication private path; that app's
        // jdtService field is null. AbstractTool's execute() with null service routes to
        // PROJECT_NOT_LOADED (no static instance => loadingState defaults to NOT_LOADED).
        // The contract: every tool MUST return a ToolResponse with success=false and a
        // documented error code. No tool may throw, return null, or return success=true
        // when no service is loaded.
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
            // Some tools without service dependency (health_check, load_project) may
            // legitimately return success. They're exempt from the success-without-service
            // ban, but they still must produce well-formed responses.
            if (resp.isSuccess()) {
                if (!isServiceIndependent(name)) {
                    successWithoutService.add(name);
                }
                continue;
            }
            // Error path: code must be present and recognized.
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

    /** Tools that legitimately operate without a loaded project. */
    private static boolean isServiceIndependent(String name) {
        // health_check reports current loading state — no project required.
        // load_project triggers a load — also no project required as input.
        return "health_check".equals(name) || "load_project".equals(name);
    }

    @Test
    @DisplayName("Every tool's name has at least one underscore-separated word or matches a single token")
    void everyTool_nameHasReasonableShape() {
        // Catches typos like single-character or unreasonably-long names. Reasonable shape:
        // 2 <= length <= 60.
        Set<String> bad = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            if (name.length() < 2 || name.length() > 60) {
                bad.add(name + " (len=" + name.length() + ")");
            }
        }
        assertTrue(bad.isEmpty(), "Tools with unreasonable name length: " + bad);
    }
}

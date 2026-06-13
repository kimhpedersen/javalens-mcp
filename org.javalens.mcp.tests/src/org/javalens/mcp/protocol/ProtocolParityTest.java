package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ToolInvocationInputs;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Universal MCP-protocol parity: EVERY registered tool is driven through the
 * real {@link McpProtocolHandler#processMessage} with a known-valid input and
 * must return a well-formed JSON-RPC envelope whose tool payload is either a
 * success or a documented error code.
 *
 * <p>This is both a backfill (the pre-1.4.2 tools never had protocol coverage)
 * and a permanent gate: a newly registered tool with no entry in
 * {@link ToolInvocationInputs} fails {@link #everyRegisteredToolHasAnInput},
 * so the per-tool protocol rule can no longer silently lapse.
 */
class ProtocolParityTest {

    /** Documented tool error codes (ErrorInfo). A protocol failure must be one of these. */
    private static final Set<String> DOCUMENTED_ERROR_CODES = Set.of(
        "PROJECT_NOT_LOADED", "PROJECT_LOADING", "PROJECT_LOAD_FAILED",
        "FILE_NOT_FOUND", "SYMBOL_NOT_FOUND", "INVALID_COORDINATES",
        "INVALID_PARAMETER", "SECURITY_VIOLATION", "TIMEOUT", "INTERNAL_ERROR",
        "REFACTORING_FAILED", "RELOAD_REQUIRED", "VERIFICATION_FAILED");

    private static TestProjectHelper helper;
    private static ToolRegistry registry;
    private static McpProtocolHandler handler;
    private static ObjectMapper objectMapper;
    private static Map<String, ObjectNode> inputs;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        helper = new TestProjectHelper();
        helper.beforeEach(null);
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path projectPath = helper.getFixturePath("simple-maven");

        JavaLensApplication app = new JavaLensApplication();
        Field svcField = JavaLensApplication.class.getDeclaredField("jdtService");
        svcField.setAccessible(true);
        svcField.set(app, service);
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        ToolRegistry r = new ToolRegistry();
        registryField.set(app, r);
        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        registry = r;

        handler = new McpProtocolHandler(registry);
        handler.processMessage("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}");

        inputs = ToolInvocationInputs.buildValidInputs(objectMapper, projectPath);
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() throws Exception {
        helper.afterEach(null);
    }

    @Test
    @DisplayName("every registered tool has a known-valid input (the gate)")
    void everyRegisteredToolHasAnInput() {
        Set<String> missing = new TreeSet<>(registry.getToolNames());
        missing.removeAll(inputs.keySet());
        assertTrue(missing.isEmpty(),
            "Tools registered with no ToolInvocationInputs entry - add one so protocol "
                + "parity covers them: " + missing);
    }

    @Test
    @DisplayName("every registered tool answers a well-formed JSON-RPC payload through processMessage")
    void everyRegisteredToolAnswersThroughEnvelope() throws Exception {
        Map<String, String> malformed = new TreeMap<>();

        int id = 1;
        for (String name : new TreeSet<>(registry.getToolNames())) {
            ObjectNode args = inputs.get(name);
            if (args == null) {
                continue; // covered by the gate test
            }
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", id++);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", name);
            params.set("arguments", args);

            String response = handler.processMessage(objectMapper.writeValueAsString(call));
            try {
                JsonNode rpc = objectMapper.readTree(response);
                if (rpc.has("error")) {
                    malformed.put(name, "JSON-RPC envelope error: " + rpc.get("error"));
                    continue;
                }
                JsonNode content = rpc.path("result").path("content");
                if (!content.isArray() || content.isEmpty()) {
                    malformed.put(name, "no content array");
                    continue;
                }
                JsonNode payload = objectMapper.readTree(content.get(0).path("text").asText());
                if (!payload.has("success")) {
                    malformed.put(name, "payload missing 'success'");
                    continue;
                }
                if (!payload.get("success").asBoolean()) {
                    String code = payload.path("error").path("code").asText("");
                    if (!DOCUMENTED_ERROR_CODES.contains(code)) {
                        malformed.put(name, "undocumented error code: '" + code + "'");
                    }
                }
            } catch (Exception e) {
                malformed.put(name, "unparseable response: " + e.getMessage());
            }
        }

        assertTrue(malformed.isEmpty(),
            "Tools whose protocol response was not well-formed: " + malformed);
    }
}

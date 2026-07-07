package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.PayloadCanonicalizer;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.fixtures.TestRegistryBuilder;
import org.javalens.mcp.tools.ToolInvocationInputs;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Universal MCP-protocol parity, BEHAVIORAL: EVERY registered tool is driven
 * through the real {@link McpProtocolHandler#processMessage} AND through a
 * direct {@code execute()} on the same registry with the same input, and the
 * two payloads must be identical (modulo timestamps). The direct execute()
 * path is the one the per-tool behavior tests already validate against known
 * fixtures, so this proves the MCP envelope delivers the real, tested result
 * for every tool — not merely a well-formed shape. A shape-only check would
 * pass a tool that returns a successful-but-wrong answer (the #32 class).
 *
 * <p>Also a permanent gate: a newly registered tool with no entry in
 * {@link ToolInvocationInputs} fails {@link #everyRegisteredToolHasAnInput},
 * so the per-tool protocol rule can no longer silently lapse.
 */
class ProtocolParityTest {

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

        registry = TestRegistryBuilder.buildRegistry(service);

        handler = new McpProtocolHandler(registry);
        handler.processMessage("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}");

        inputs = ToolInvocationInputs.buildValidInputs(objectMapper, projectPath);
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() throws Exception {
        helper.afterEach(null);
    }

    /** Literal anchor: the exact number of tools the MCP surface must expose. */
    private static final int EXPECTED_TOOL_COUNT = 75;

    @Test
    @DisplayName("registration anchor: exactly 75 tools, and the registry set equals the covered set")
    void registrationAnchor() {
        // A LITERAL count, not derived from the registry - so a tool deleted
        // from registerTools() (count drops 75->74) fails here instead of
        // shipping green (the count-derived assertions elsewhere cannot).
        assertEquals(EXPECTED_TOOL_COUNT, registry.getToolNames().size(),
            "the registered tool count changed - if intentional, update EXPECTED_TOOL_COUNT and "
                + "ToolInvocationInputs; otherwise a tool was added/removed from registerTools()");

        // Bidirectional set equality catches a SWAP (one removed, one added,
        // count unchanged): the real registry must equal the covered set.
        Set<String> registered = new TreeSet<>(registry.getToolNames());
        Set<String> covered = new TreeSet<>(inputs.keySet());
        assertEquals(covered, registered,
            "the set of registered tools diverged from the covered/known set - a tool was added "
                + "without an input, or removed from the registry while still listed");
    }

    @Test
    @DisplayName("every tool delivers through the MCP envelope the same result its execute() path produces")
    void everyRegisteredToolBehavesIdenticallyThroughEnvelope() throws Exception {
        Map<String, String> divergent = new TreeMap<>();
        int compared = 0;

        int id = 1;
        for (String name : new TreeSet<>(registry.getToolNames())) {
            ObjectNode args = inputs.get(name);
            if (args == null) {
                continue; // covered by the gate test
            }
            compared++;

            // Behavioral oracle: the direct execute() result, the path the
            // per-tool behavior tests validate against known fixtures.
            org.javalens.mcp.models.ToolResponse direct =
                registry.getTool(name).orElseThrow().execute(args.deepCopy());
            String expected = PayloadCanonicalizer.canonical(objectMapper.valueToTree(direct));

            // Envelope path: the same input through processMessage.
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", id++);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", name);
            params.set("arguments", args.deepCopy());
            String response = handler.processMessage(objectMapper.writeValueAsString(call));

            try {
                JsonNode rpc = objectMapper.readTree(response);
                if (rpc.has("error")) {
                    divergent.put(name, "JSON-RPC envelope error: " + rpc.get("error"));
                    continue;
                }
                JsonNode content = rpc.path("result").path("content");
                if (!content.isArray() || content.isEmpty()) {
                    divergent.put(name, "no content array");
                    continue;
                }
                String actual = PayloadCanonicalizer.canonical(objectMapper.readTree(content.get(0).path("text").asText()));
                if (!expected.equals(actual)) {
                    divergent.put(name, "envelope payload != execute() payload\n  execute=" + expected
                        + "\n  envelope=" + actual);
                }
            } catch (Exception e) {
                divergent.put(name, "unparseable response: " + e.getMessage());
            }
        }

        assertTrue(divergent.isEmpty(),
            "Tools whose MCP-envelope behavior diverged from their execute() result: " + divergent);

        // Provably ALL of them: the behavioral comparison ran for every
        // registered tool, none silently skipped. Combined with the gate test,
        // coverage cannot quietly shrink as tools are added.
        assertEquals(registry.getToolNames().size(), compared,
            "every registered tool must be behaviorally compared through the envelope");
    }

    /**
     * Path segments that betray a JDT search-scope leak: JavaLens's own Eclipse
     * workspace metadata, which lives under the build directory and must NEVER
     * appear in any tool payload (it is not part of the user's project). The
     * find_reflection_usage golden froze 839 such entries; this gate makes that
     * class of leak fail the build for EVERY tool at once.
     */
    private static final java.util.List<String> FORBIDDEN_PATH_SEGMENTS =
        java.util.List.of("javalens-WS", "target/work", "target\\work", ".metadata");

    @Test
    @DisplayName("fixture-scope gate: no tool payload leaks a path into the JDT workspace metadata")
    void noToolPayloadEscapesTheFixture() throws Exception {
        Map<String, String> leaks = new TreeMap<>();
        int id = 1;
        for (String name : new TreeSet<>(registry.getToolNames())) {
            ObjectNode args = inputs.get(name);
            if (args == null) {
                continue;
            }
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", id++);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", name);
            params.set("arguments", args.deepCopy());
            String response = handler.processMessage(objectMapper.writeValueAsString(call));

            JsonNode rpc = objectMapper.readTree(response);
            JsonNode content = rpc.path("result").path("content");
            if (!content.isArray() || content.isEmpty()) {
                continue; // error envelopes carry no payload paths to leak
            }
            JsonNode payload = objectMapper.readTree(content.get(0).path("text").asText());
            String leak = firstLeakingValue(payload);
            if (leak != null) {
                leaks.put(name, leak);
            }
        }
        assertTrue(leaks.isEmpty(),
            "Tools whose payload leaked a path into the JDT workspace metadata (search-scope "
                + "escape — the find_reflection_usage class): " + leaks);
    }

    @Test
    @DisplayName("non-degeneracy gate: every tool returns populated success data OR an explicitly-coded refusal — never a hollow success")
    void noToolReturnsAHollowSuccess() throws Exception {
        Map<String, String> degenerate = new TreeMap<>();
        int id = 1;
        for (String name : new TreeSet<>(registry.getToolNames())) {
            ObjectNode args = inputs.get(name);
            if (args == null) {
                continue;
            }
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", id++);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", name);
            params.set("arguments", args.deepCopy());
            JsonNode rpc = objectMapper.readTree(handler.processMessage(objectMapper.writeValueAsString(call)));

            JsonNode content = rpc.path("result").path("content");
            if (!content.isArray() || content.isEmpty()) {
                degenerate.put(name, "no content array in JSON-RPC result");
                continue;
            }
            JsonNode payload = objectMapper.readTree(content.get(0).path("text").asText());
            boolean success = payload.path("success").asBoolean(false);
            if (success) {
                // A success must carry real content — not a hollow {} / [] masquerading
                // as coverage (the degenerate-golden class this gate makes unrepeatable).
                JsonNode data = payload.get("data");
                if (data == null || data.isNull() || isStructurallyEmpty(data)) {
                    degenerate.put(name, "success with empty data: " + payload);
                }
            } else {
                // A refusal is non-degenerate ONLY if it is explicitly coded — never a
                // silent failure. The tool's real capability is pinned by its own
                // authored envelope_ test on the fixture where it applies.
                String code = payload.path("error").path("code").asText("");
                if (code.isBlank()) {
                    degenerate.put(name, "uncoded failure: " + payload);
                }
            }
        }
        assertTrue(degenerate.isEmpty(),
            "Tools whose curated parity input yields a degenerate payload (hollow success or "
                + "uncoded failure) — upgrade the ToolInvocationInputs entry to exercise the tool, "
                + "or confirm the refusal is explicitly coded: " + degenerate);
    }

    /** An object with no fields or an array with no elements — a structurally-empty payload. */
    private static boolean isStructurallyEmpty(JsonNode data) {
        if (data.isObject() || data.isArray()) {
            return data.isEmpty();
        }
        return false;
    }

    /** Recursively returns the first string value containing a forbidden workspace segment, or null. */
    private static String firstLeakingValue(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                String leak = firstLeakingValue(it.next().getValue());
                if (leak != null) return leak;
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode e : node) {
                String leak = firstLeakingValue(e);
                if (leak != null) return leak;
            }
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            for (String forbidden : FORBIDDEN_PATH_SEGMENTS) {
                if (text.contains(forbidden)) {
                    return text;
                }
            }
        }
        return null;
    }

}

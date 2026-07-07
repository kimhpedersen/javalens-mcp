package org.javalens.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.SessionManager;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 (multiplexing.md): the minimum-viable Streamable HTTP transport.
 * Running inside {@code org.javalens.mcp.tests}' real Tycho/Equinox OSGi
 * launch also doubles as the OSGi-visibility spike the plan calls for —
 * {@code com.sun.net.httpserver} must resolve as an Import-Package of the
 * {@code org.javalens.mcp} bundle from the system bundle, not just on a
 * plain classpath. If this suite can't even start the server, that's the
 * signal to fall back to {@code org.eclipse.equinox.http.jetty}.
 */
class McpHttpTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private ProjectRegistry projectRegistry;
    private SessionManager sessionManager;
    private McpHttpTransport transport;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        projectRegistry = new ProjectRegistry();
        sessionManager = new SessionManager(new ToolRegistry(), projectRegistry);
        transport = new McpHttpTransport(sessionManager,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        transport.start();
        baseUrl = "http://127.0.0.1:" + transport.getPort() + "/mcp";
    }

    @AfterEach
    void tearDown() {
        transport.close();
        sessionManager.close();
        projectRegistry.close();
    }

    @Test
    @DisplayName("initialize without a session header succeeds and returns a fresh Mcp-Session-Id")
    void initialize_returnsFreshSessionIdHeader() throws Exception {
        HttpResponse<String> response = post(null, initializeBody(0));

        assertEquals(200, response.statusCode());
        String sessionId = response.headers().firstValue(McpHttpTransport.SESSION_HEADER).orElse(null);
        assertNotNull(sessionId, "initialize response must carry a fresh Mcp-Session-Id header");
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));

        JsonNode json = objectMapper.readTree(response.body());
        assertTrue(json.has("result"), "initialize must return a JSON-RPC result: " + response.body());
    }

    @Test
    @DisplayName("a non-initialize request with no Mcp-Session-Id returns 404")
    void nonInitialize_missingSessionId_returns404() throws Exception {
        HttpResponse<String> response = post(null, toolsListBody(1));
        assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("a request carrying an unknown Mcp-Session-Id returns 404")
    void unknownSessionId_returns404() throws Exception {
        HttpResponse<String> response = post("not-a-real-session", toolsListBody(1));
        assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("a subsequent request reusing the returned Mcp-Session-Id succeeds")
    void subsequentRequest_withValidSessionId_succeeds() throws Exception {
        String sessionId = initializeAndGetSessionId();

        HttpResponse<String> response = post(sessionId, toolsListBody(2));

        assertEquals(200, response.statusCode());
        JsonNode json = objectMapper.readTree(response.body());
        assertTrue(json.has("result"), "tools/list must return a JSON-RPC result: " + response.body());
    }

    @Test
    @DisplayName("two concurrent clients each get their own session and can both operate at once")
    void twoConcurrentClients_getIsolatedSessions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(this::initializeAndGetSessionId);
            Future<String> f2 = pool.submit(this::initializeAndGetSessionId);
            String session1 = f1.get(10, TimeUnit.SECONDS);
            String session2 = f2.get(10, TimeUnit.SECONDS);

            assertNotEquals(session1, session2, "each client must get its own session id");
            assertEquals(200, post(session1, toolsListBody(3)).statusCode());
            assertEquals(200, post(session2, toolsListBody(3)).statusCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("DELETE terminates a session; the same id then 404s")
    void delete_terminatesSession() throws Exception {
        String sessionId = initializeAndGetSessionId();

        HttpResponse<String> deleteResponse = delete(sessionId);
        assertEquals(200, deleteResponse.statusCode());

        HttpResponse<String> afterDelete = post(sessionId, toolsListBody(4));
        assertEquals(404, afterDelete.statusCode(), "a terminated session id must no longer be usable");
    }

    @Test
    @DisplayName("DELETE with an unknown Mcp-Session-Id returns 404")
    void delete_unknownSessionId_returns404() throws Exception {
        assertEquals(404, delete("not-a-real-session").statusCode());
    }

    private String initializeAndGetSessionId() throws Exception {
        HttpResponse<String> response = post(null, initializeBody(0));
        return response.headers().firstValue(McpHttpTransport.SESSION_HEADER).orElseThrow();
    }

    private HttpResponse<String> post(String sessionId, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header(McpHttpTransport.SESSION_HEADER, sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
            .header(McpHttpTransport.SESSION_HEADER, sessionId)
            .DELETE()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String initializeBody(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{}}";
    }

    private static String toolsListBody(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}";
    }
}

package org.javalens.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.javalens.mcp.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP's Streamable HTTP transport, minimum viable slice: a single
 * {@code POST /mcp} endpoint (+ optional {@code DELETE /mcp}), plain
 * {@code application/json} responses (no SSE — nothing in this codebase
 * originates unsolicited server->client notifications today, so the SSE
 * stream Streamable HTTP allows for server push isn't needed for parity
 * with today's stdio behavior; see multiplexing.md).
 *
 * <p>Session resolution follows the spec: the {@code initialize} request may
 * arrive with no {@code Mcp-Session-Id} header, in which case a new
 * {@link Session} is created and its id is returned via the
 * {@code Mcp-Session-Id} response header; every subsequent request from that
 * client must send it back. A non-initialize request with a missing or
 * unknown session id gets {@code 404} — the client must re-initialize.
 *
 * <p>Each request binds {@link SessionContext} to the resolved session for
 * the duration of {@link McpProtocolHandler#processMessage}, so every tool
 * invoked from that request sees the right session (and, transitively, the
 * right attached {@code LoadedProject}) exactly like the stdio transport's
 * single ambient session does today — just re-resolved per request instead
 * of bound once for the process.
 */
public class McpHttpTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    /** Per the MCP Streamable HTTP transport spec. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String MCP_PATH = "/mcp";
    private static final int DEFAULT_THREAD_POOL_SIZE = 16;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionManager sessionManager;
    private final HttpServer httpServer;
    private final ExecutorService executor;

    public McpHttpTransport(SessionManager sessionManager, InetSocketAddress bindAddress) throws IOException {
        this(sessionManager, bindAddress, DEFAULT_THREAD_POOL_SIZE);
    }

    public McpHttpTransport(SessionManager sessionManager, InetSocketAddress bindAddress, int threadPoolSize)
            throws IOException {
        this.sessionManager = sessionManager;
        this.httpServer = HttpServer.create(bindAddress, 0);
        this.executor = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "javalens-http-transport");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        httpServer.createContext(MCP_PATH, this::handle);
    }

    /** Starts accepting connections. Call once; not idempotent. */
    public void start() {
        httpServer.start();
        log.info("MCP HTTP transport listening on {}", getAddress());
    }

    /** The bound socket address, valid immediately after construction (before {@link #start()}). */
    public InetSocketAddress getAddress() {
        return httpServer.getAddress();
    }

    /** Convenience accessor for the bound port (e.g. after binding to port 0). */
    public int getPort() {
        return getAddress().getPort();
    }

    /** Stops accepting connections and shuts down the request thread pool. Idempotent. */
    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> sendResponse(exchange, 405, "");
            }
        } catch (Exception e) {
            log.error("Unhandled error processing {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            sendResponse(exchange, 500, "");
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);

        Session session;
        boolean freshlyCreated;
        if (sessionId == null) {
            if (!isInitializeRequest(body)) {
                // Spec: a non-initialize request with no session id must fail so the
                // client re-initializes, rather than silently running unattached.
                sendResponse(exchange, 404, "");
                return;
            }
            session = sessionManager.create();
            freshlyCreated = true;
        } else {
            Optional<Session> existing = sessionManager.get(sessionId);
            if (existing.isEmpty()) {
                sendResponse(exchange, 404, "");
                return;
            }
            session = existing.get();
            freshlyCreated = false;
        }

        SessionContext.bind(session);
        try {
            String response = session.getProtocolHandler().processMessage(body);
            if (freshlyCreated) {
                exchange.getResponseHeaders().add(SESSION_HEADER, session.getId());
            }
            if (response == null) {
                // Notification: no JSON-RPC response body, per processMessage's contract.
                sendResponse(exchange, 202, "");
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(exchange, 200, response);
            }
        } finally {
            SessionContext.clear();
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (sessionId == null || sessionManager.get(sessionId).isEmpty()) {
            sendResponse(exchange, 404, "");
            return;
        }
        sessionManager.terminate(sessionId);
        sendResponse(exchange, 200, "");
    }

    /** True only for a well-formed JSON-RPC {@code initialize} request; malformed bodies fail closed (false). */
    private boolean isInitializeRequest(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.has("method") && "initialize".equals(node.get("method").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

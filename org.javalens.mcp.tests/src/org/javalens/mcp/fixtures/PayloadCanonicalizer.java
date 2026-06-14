package org.javalens.mcp.fixtures;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical string form of a tool payload: object keys sorted, array elements
 * sorted, volatile and null fields dropped. Two payloads with identical content
 * compare equal regardless of field or element order; genuine content divergence
 * still differs.
 *
 * <p>Shared by {@code ProtocolParityTest} (the global behavioral-parity gate) and
 * {@link EnvelopeHarness#assertEnvelopeFidelity} (the per-tool full-payload
 * fidelity check), so the wire-vs-execute() comparison is defined in exactly one
 * place and both seams use the identical equivalence.
 */
public final class PayloadCanonicalizer {

    /** Time-varying fields that legitimately differ between two calls; scrubbed before compare. */
    public static final Set<String> VOLATILE_FIELDS =
        Set.of("uptime", "startedAt", "startTime", "loadedAt");

    private PayloadCanonicalizer() {
    }

    public static String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            TreeMap<String, String> entries = new TreeMap<>();
            node.fields().forEachRemaining(e -> {
                if (!VOLATILE_FIELDS.contains(e.getKey()) && !e.getValue().isNull()) {
                    entries.put(e.getKey(), canonical(e.getValue()));
                }
            });
            return entries.toString();
        }
        if (node.isArray()) {
            List<String> elements = new ArrayList<>();
            node.forEach(e -> elements.add(canonical(e)));
            Collections.sort(elements);
            return elements.toString();
        }
        return node.toString();
    }
}

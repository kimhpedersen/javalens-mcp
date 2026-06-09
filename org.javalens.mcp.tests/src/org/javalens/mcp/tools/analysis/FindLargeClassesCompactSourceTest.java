package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindLargeClassesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins find_large_classes on a JEP 512 compact source file: the implicitly
 * declared class is an {@code ImplicitTypeDeclaration} and is scanned via
 * {@code AbstractTypeDeclaration}, so its members are counted. Thresholds are
 * lowered so the implicit class CompactMain (two methods, one field) is flagged,
 * letting the test assert its exact member counts by name.
 */
class FindLargeClassesCompactSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindLargeClassesTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new FindLargeClassesTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("compact source file: implicit class is scanned and its members counted")
    @SuppressWarnings("unchecked")
    void compactSource_implicitClassScanned() {
        ObjectNode args = mapper.createObjectNode();
        args.put("maxMethods", 1); // CompactMain has 2 methods -> flagged
        args.put("maxFields", 0);
        args.put("maxLines", 0);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> large = (List<Map<String, Object>>) data.get("largeClasses");

        Map<String, Object> compact = large.stream()
            .filter(e -> "CompactMain".equals(e.get("typeName")))
            .findFirst().orElse(null);
        assertNotNull(compact,
            "the implicit class CompactMain must be scanned and flagged; got: " + large);
        assertEquals(2, ((Number) compact.get("methodCount")).intValue(),
            "implicit class has main() and message(); got: " + compact);
        assertEquals(1, ((Number) compact.get("fieldCount")).intValue(),
            "implicit class has the greeting field; got: " + compact);
        assertTrue(((Number) compact.get("lineCount")).intValue() > 0,
            "implicit class must report a real line span (not -1 from a synthetic node); got: " + compact);
    }
}

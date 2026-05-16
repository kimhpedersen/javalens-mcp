package org.javalens.core;

import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TypeKindResolverTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("regular class returns 'class'")
    void classKind() {
        IType t = service.findType("com.example.Calculator");
        assertNotNull(t, "Calculator fixture must resolve");
        assertEquals("class", TypeKindResolver.kindOf(t));
    }

    @Test
    @DisplayName("interface returns 'interface'")
    void interfaceKind() {
        IType t = service.findType("com.example.IShape");
        assertNotNull(t, "IShape fixture must resolve");
        assertEquals("interface", TypeKindResolver.kindOf(t));
    }

    @Test
    @DisplayName("enum returns 'enum'")
    void enumKind() {
        IType t = service.findType("com.example.TypeKindsFixture.Color");
        assertNotNull(t, "TypeKindsFixture.Color enum must resolve");
        assertEquals("enum", TypeKindResolver.kindOf(t));
    }

    @Test
    @DisplayName("record returns 'record'")
    void recordKind() {
        IType t = service.findType("com.example.Point");
        assertNotNull(t, "Point record fixture must resolve");
        assertEquals("record", TypeKindResolver.kindOf(t));
    }

    @Test
    @DisplayName("annotation returns 'annotation' (NOT 'interface' — ordering quirk)")
    void annotationKind_topLevel() {
        IType t = service.findType("com.example.Marker");
        assertNotNull(t, "Marker @interface fixture must resolve");
        // JDT reports isAnnotation()=true AND isInterface()=true for @interface types.
        // kindOf must check isAnnotation first; if it didn't, this would return 'interface'.
        assertEquals("annotation", TypeKindResolver.kindOf(t));
    }

    @Test
    @DisplayName("nested annotation returns 'annotation' (regression: nested branch was missing isAnnotation check)")
    void annotationKind_nested() {
        IType t = service.findType("com.example.AnnotationsFixture.Tag");
        assertNotNull(t, "AnnotationsFixture.Tag nested @interface must resolve");
        // GetTypeMembersTool.java:346 previously classified this as 'interface' because
        // the nested-type branch omitted the isAnnotation() first-check. Consolidation
        // through this resolver fixes that bug; this test prevents regression.
        assertEquals("annotation", TypeKindResolver.kindOf(t));
    }
}

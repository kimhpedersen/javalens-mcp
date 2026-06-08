package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that JavaLens parses and analyzes Java 25 source on the upgraded JDT
 * (Eclipse 2025-12). The fixture uses a Java 25 <em>standard</em> language feature
 * — flexible constructor bodies (JEP 513), a statement before the explicit
 * {@code super()} — which is a syntax error prior to Java 25. JDT accepting it at
 * compliance 25 with no error-severity problems is the proof of Java 25 support.
 */
class Java25SupportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Java 25 project declares compliance 25")
    void java25_complianceIs25() throws Exception {
        var fixture = helper.loadFixture("java25-maven");
        assertEquals("25", fixture.classpath().compilerCompliance(),
            "java25-maven declares maven.compiler.release=25; compliance must follow");
    }

    @Test
    @DisplayName("Java 25 flexible-constructor-body source resolves into the model")
    void java25_typeResolves() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");

        IType type = service.findType("com.example.FlexibleCtorDemo");
        assertNotNull(type, "findType must resolve com.example.FlexibleCtorDemo");
        assertTrue(type.exists(), "FlexibleCtorDemo IType must report exists()");
        // If the flexible constructor body (statement before super()) failed to parse,
        // the constructor and method would be missing from the model.
        assertEquals(1, type.getMethods().length == 0 ? 0 : Arrays.stream(type.getMethods())
            .filter(m -> m.getElementName().equals("value")).count(),
            "value() method must be present, proving the body parsed");
        assertTrue(Arrays.stream(type.getMethods()).anyMatch(m -> {
            try { return m.isConstructor(); } catch (Exception e) { return false; }
        }), "the flexible-body constructor must be present in the model");
    }

    @Test
    @DisplayName("Java 25 flexible constructor body parses with no error-severity diagnostics")
    void java25_noErrorDiagnostics() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");

        IType type = service.findType("com.example.FlexibleCtorDemo");
        assertNotNull(type);
        ICompilationUnit cu = type.getCompilationUnit();
        assertNotNull(cu, "FlexibleCtorDemo must have a compilation unit");

        boolean wasWorkingCopy = cu.isWorkingCopy();
        if (!wasWorkingCopy) {
            cu.becomeWorkingCopy(null);
        }
        CompilationUnit ast;
        try {
            ast = cu.reconcile(AST.getJLSLatest(), ICompilationUnit.FORCE_PROBLEM_DETECTION, null, null);
        } finally {
            if (!wasWorkingCopy) {
                cu.discardWorkingCopy();
            }
        }
        assertNotNull(ast, "reconcile must return an AST for the Java 25 source");

        String errors = Arrays.stream(ast.getProblems())
            .filter(IProblem::isError)
            .map(IProblem::getMessage)
            .collect(Collectors.joining("; "));
        assertEquals("", errors,
            "Java 25 flexible constructor body must parse with no error-severity diagnostics; got: " + errors);
    }
}

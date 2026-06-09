package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that JavaLens resolves Lombok-generated members. Lombok contributes its
 * generated accessors by patching JDT's compiler at JVM startup via its agent
 * ({@code -javaagent:lombok.jar=ECJ}); the build attaches that agent to the test
 * JVM (see this module's pom). Without it, {@code @Data} types expose no members
 * and every call to a generated accessor is a false "undefined" error — the same
 * false-error class as a missing JRE. With it, the generated members are part of
 * the model and dependent code resolves cleanly.
 */
class LombokSupportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("@Data class exposes Lombok-generated accessors in the JDT model")
    void lombokData_generatedAccessorsPresent() throws Exception {
        JdtServiceImpl service = helper.loadProject("lombok-maven");

        IType bean = service.findType("com.example.LombokBean");
        assertNotNull(bean, "LombokBean must resolve");

        Set<String> methodNames = Arrays.stream(bean.getMethods())
            .map(IMethod::getElementName)
            .collect(Collectors.toSet());

        // @Data generates getters/setters for each field plus equals/hashCode/toString.
        for (String expected : List.of("getName", "getAge", "setName", "setAge",
                "equals", "hashCode", "toString")) {
            assertTrue(methodNames.contains(expected),
                "Lombok-generated method " + expected + " must be in the model; got: " + methodNames);
        }
    }

    @Test
    @DisplayName("code calling Lombok-generated accessors compiles with no false errors")
    void lombokConsumer_noFalseUndefinedErrors() throws Exception {
        JdtServiceImpl service = helper.loadProject("lombok-maven");

        IType consumer = service.findType("com.example.LombokConsumer");
        assertNotNull(consumer, "LombokConsumer must resolve");
        ICompilationUnit cu = consumer.getCompilationUnit();
        assertNotNull(cu);

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
        assertNotNull(ast, "reconcile must return an AST");

        String errors = Arrays.stream(ast.getProblems())
            .filter(IProblem::isError)
            .map(IProblem::getMessage)
            .collect(Collectors.joining(" | "));
        assertEquals("", errors,
            "calls to Lombok-generated getName()/getAge() must resolve; got errors: " + errors);
    }
}

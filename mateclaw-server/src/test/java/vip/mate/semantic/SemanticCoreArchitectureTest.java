package vip.mate.semantic;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticCoreArchitectureTest {

    private static final String APPLICATION_PACKAGE = "vip.mate.semantic.application";
    private static final String CORE_PACKAGE = "vip.mate.semantic.core";
    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CORE_PACKAGE);
    private static final ArchRule JDK_ONLY_RULE = classes()
            .should(haveOnlyAllowedDependencies(false))
            .because("the semantic core must remain independent from frameworks and the MateClaw host");

    @Test
    void scansRealSemanticCoreProductionClasses() {
        assertFalse(CORE_CLASSES.isEmpty(), "architecture guard must scan semantic core production classes");
    }

    @Test
    void semanticCoreHasOnlyJdkAndOwnPackageDependencies() {
        JDK_ONLY_RULE.check(CORE_CLASSES);
    }

    @Test
    void architectureRuleRejectsAForbiddenDependency() {
        JavaClasses canary = new ClassFileImporter().importClasses(ForbiddenSpringDependency.class);

        AssertionError error = assertThrows(AssertionError.class, () -> JDK_ONLY_RULE.check(canary));
        assertTrue(error.getMessage().contains(ApplicationContext.class.getName()));
    }

    @Test
    void semanticApplicationDependsOnlyOnItselfCoreAndJdk() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(APPLICATION_PACKAGE);
        assertFalse(application.isEmpty(), "architecture guard must scan application production classes");
        classes().should(haveOnlyAllowedDependencies(true)).check(application);
    }

    @Test
    void applicationRuleRejectsAForbiddenDependency() {
        JavaClasses canary = new ClassFileImporter().importClasses(ForbiddenSpringDependency.class);
        AssertionError error = assertThrows(AssertionError.class,
                () -> classes().should(haveOnlyAllowedDependencies(true)).check(canary));
        assertTrue(error.getMessage().contains(ApplicationContext.class.getName()));
    }

    private static ArchCondition<JavaClass> haveOnlyAllowedDependencies(boolean application) {
        return new ArchCondition<>("have only JDK or semantic core dependencies") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass().getBaseComponentType();
                    if (targetClass.isPrimitive()) {
                        continue;
                    }
                    String target = targetClass.getName();
                    if (isAllowed(target) || application && target.startsWith(APPLICATION_PACKAGE + ".")) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency,
                            javaClass.getName() + " depends on forbidden type " + target));
                }
            }
        };
    }

    private static boolean isAllowed(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith(CORE_PACKAGE + ".");
    }

    private static final class ForbiddenSpringDependency {
        private final ApplicationContext context = null;
    }
}

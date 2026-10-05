package vip.mate.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import vip.mate.agent.architecturefixture.RuntimeCanaries;
import vip.mate.presales.architecturefixture.ControllerCanaries;
import vip.mate.semantic.application.architecturefixture.AllowedExtractionPortDependency;
import vip.mate.semantic.core.architecturefixture.ForbiddenFrameworkDependency;

/** Compiled AQ07 checks over production bytecode; test-only canaries prove failure behavior. */
class WorkbenchArchitectureTest {
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("vip.mate");
    private static final DescribedPredicate<JavaClass> RUNTIME =
            packages(
                    "runtime and shared host",
                    "vip.mate.agent",
                    "vip.mate.common",
                    "vip.mate.auth",
                    "vip.mate.workspace",
                    "vip.mate.tool",
                    "vip.mate.skill");
    private static final DescribedPredicate<JavaClass> WORKBENCH =
            packages(
                    "concrete workbench",
                    "vip.mate.presales",
                    "vip.mate.bidding",
                    "vip.mate.delivery");
    private static final DescribedPredicate<JavaClass> PURE_SEMANTIC =
            packages(
                    "pure semantic core/application",
                    "vip.mate.semantic.core",
                    "vip.mate.semantic.application");
    private static final DescribedPredicate<JavaClass> CONTROLLER =
            new DescribedPredicate<>("workbench Controller") {
                @Override
                public boolean test(JavaClass type) {
                    return WORKBENCH.test(type)
                            && (type.getSimpleName().endsWith("Controller")
                                    || type.isAnnotatedWith(
                                            "org.springframework.web.bind.annotation.RestController"));
                }
            };
    private static final DescribedPredicate<JavaClass> FRAMEWORK_PERSISTENCE =
            packages(
                    "persistence frameworks",
                    "org.springframework.jdbc",
                    "java.sql",
                    "javax.sql",
                    "jakarta.persistence",
                    "javax.persistence",
                    "org.apache.ibatis",
                    "com.baomidou.mybatisplus");
    private static final DescribedPredicate<JavaClass> SPRING =
            packages("Spring", "org.springframework");
    private static final DescribedPredicate<JavaClass> PERSISTENCE =
            new DescribedPredicate<>("persistence access") {
                @Override
                public boolean test(JavaClass input) {
                    JavaClass type = input.getBaseComponentType();
                    String name = type.getName();
                    return FRAMEWORK_PERSISTENCE.test(type)
                            || name.matches("vip[.]mate[.].*[.](?:repository|mapper|dao)[.].*")
                            || name.matches(
                                    "vip[.]mate[.].*(?:Repository|Mapper|Dao|DAO)(?:\\$.*)?");
                }
            };
    private static final DescribedPredicate<JavaClass> SEMANTIC_FORBIDDEN =
            new DescribedPredicate<>("frameworks, persistence or workbenches") {
                @Override
                public boolean test(JavaClass input) {
                    JavaClass type = input.getBaseComponentType();
                    return WORKBENCH.test(type)
                            || FRAMEWORK_PERSISTENCE.test(type)
                            || SPRING.test(type);
                }
            };
    private static final ArchRule RUNTIME_RULE =
            noClasses().that(RUNTIME).should().dependOnClassesThat(WORKBENCH);
    private static final ArchRule CONTROLLER_RULE =
            noClasses().that(CONTROLLER).should().dependOnClassesThat(PERSISTENCE);
    private static final ArchRule SEMANTIC_RULE =
            noClasses().that(PURE_SEMANTIC).should().dependOnClassesThat(SEMANTIC_FORBIDDEN);

    @Test
    void coreRuntimeMustNotDependOnConcreteWorkbenches() {
        requireScope(PRODUCTION, RUNTIME);
        RUNTIME_RULE.check(PRODUCTION);
    }

    @Test
    void workbenchControllersMustNotAccessPersistence() {
        requireScope(PRODUCTION, CONTROLLER);
        CONTROLLER_RULE.check(PRODUCTION);
    }

    @Test
    void semanticPureCoreMustRemainFrameworkIndependent() {
        requireScope(PRODUCTION, PURE_SEMANTIC);
        SEMANTIC_RULE.check(PRODUCTION);
    }

    @Test
    void runtimeRuleRejectsAConcreteWorkbenchDependency() {
        rejects(
                RUNTIME_RULE,
                RUNTIME,
                RuntimeCanaries.ForbiddenWorkbenchDependency.class,
                "vip.mate.presales.PresalesService");
    }

    @Test
    void controllerRuleRejectsJdbcAccess() {
        rejects(
                CONTROLLER_RULE,
                CONTROLLER,
                ControllerCanaries.ForbiddenJdbcController.class,
                "org.springframework.jdbc.core.JdbcTemplate");
    }

    @Test
    void controllerRuleRejectsRootPackageRepositoryAccess() {
        rejects(
                CONTROLLER_RULE,
                CONTROLLER,
                ControllerCanaries.ForbiddenRootRepositoryController.class,
                "vip.mate.bidding.BiddingRepository");
    }

    @Test
    void controllerRuleRejectsRepositoryPackageWithoutTypeSuffix() {
        rejects(
                CONTROLLER_RULE,
                CONTROLLER,
                ControllerCanaries.ForbiddenRepositoryPackageController.class,
                "vip.mate.presales.repository.architecturefixture.QueryPort");
    }

    @Test
    void semanticMayUseItsOwnPureApplicationPort() {
        JavaClasses fixture =
                new ClassFileImporter().importClasses(AllowedExtractionPortDependency.class);
        requireScope(fixture, PURE_SEMANTIC);
        SEMANTIC_RULE.check(fixture);
    }

    @Test
    void semanticRuleRejectsAFrameworkDependency() {
        rejects(
                SEMANTIC_RULE,
                PURE_SEMANTIC,
                ForbiddenFrameworkDependency.class,
                "org.springframework.context.ApplicationContext");
    }

    @Test
    void runtimeMayUseThePublicExecutionContract() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importClasses(RuntimeCanaries.AllowedExecutionContract.class);
        requireScope(fixture, RUNTIME);
        RUNTIME_RULE.check(fixture);
    }

    @Test
    void controllerMaySerializeAndCallItsApplicationService() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importClasses(ControllerCanaries.AllowedSerializationController.class);
        requireScope(fixture, CONTROLLER);
        CONTROLLER_RULE.check(fixture);
    }

    @Test
    void emptyAndIncompleteImportsCannotClaimProductionCoverage() {
        JavaClasses empty = new ClassFileImporter().importClasses(new Class<?>[0]);
        for (var predicate : List.of(RUNTIME, CONTROLLER, PURE_SEMANTIC)) {
            AssertionError error =
                    assertThrows(AssertionError.class, () -> requireScope(empty, predicate));
            assertTrue(error.getMessage().contains("nonempty production bytecode"));
        }
        JavaClasses partial =
                new ClassFileImporter()
                        .importClasses(RuntimeCanaries.AllowedExecutionContract.class);
        assertThrows(AssertionError.class, () -> requireScope(partial, CONTROLLER));
        assertThrows(AssertionError.class, () -> requireScope(partial, PURE_SEMANTIC));
    }

    private static void rejects(
            ArchRule rule, DescribedPredicate<JavaClass> scope, Class<?> canary, String target) {
        JavaClasses fixture = new ClassFileImporter().importClasses(canary);
        requireScope(fixture, scope);
        AssertionError error = assertThrows(AssertionError.class, () -> rule.check(fixture));
        assertTrue(error.getMessage().contains(target), error.getMessage());
    }

    private static void requireScope(JavaClasses classes, DescribedPredicate<JavaClass> predicate) {
        long count = classes.stream().filter(predicate::test).count();
        if (classes == PRODUCTION) {
            System.out.println(
                    "ARCHUNIT_PRODUCTION_CLASSES scope="
                            + predicate.getDescription()
                            + " imported="
                            + classes.size()
                            + " matched="
                            + count);
        }
        assertTrue(
                count > 0,
                "Architecture checks require nonempty production bytecode for "
                        + predicate.getDescription());
    }

    private static DescribedPredicate<JavaClass> packages(String label, String... prefixes) {
        return new DescribedPredicate<>(label) {
            @Override
            public boolean test(JavaClass type) {
                String name = type.getBaseComponentType().getName();
                for (String prefix : prefixes) if (name.startsWith(prefix + ".")) return true;
                return false;
            }
        };
    }
}

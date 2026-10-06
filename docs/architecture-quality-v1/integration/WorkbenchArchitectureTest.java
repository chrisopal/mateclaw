package vip.mate.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Install after AQ-01..AQ-03 remove the legacy dependencies.
 * Uses the repository's existing ArchUnit dependency; do not add a second version.
 * Historical source template; executable installation and test-only canaries now live in
 * mateclaw-server/src/test/java/vip/mate/architecture/WorkbenchArchitectureTest.java.
 * This documentation file itself is not compiled; see AQ07_ARCHUNIT_INSTALL_ACCEPTANCE.
 */
class WorkbenchArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vip.mate");

    @Test
    void coreRuntimeMustNotDependOnConcreteWorkbenches() {
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith("vip.mate.agent")),
                "Architecture checks must not pass against an empty classpath");
        noClasses()
                .that().resideInAnyPackage("vip.mate.agent..", "vip.mate.common..",
                        "vip.mate.auth..", "vip.mate.workspace..", "vip.mate.tool..", "vip.mate.skill..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("vip.mate.presales..", "vip.mate.bidding..", "vip.mate.delivery..")
                .check(classes);
    }

    @Test
    void workbenchControllersMustNotAccessPersistence() {
        noClasses()
                .that().resideInAnyPackage("vip.mate.presales..", "vip.mate.bidding..", "vip.mate.delivery..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..",
                        "vip.mate.presales.repository..", "vip.mate.bidding.repository..",
                        "vip.mate.delivery.repository..")
                .check(classes);
    }

    @Test
    void semanticPureCoreMustRemainFrameworkIndependent() {
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith("vip.mate.semantic.core")),
                "Include semantic-core production classes when running this test");
        noClasses()
                .that().resideInAnyPackage("vip.mate.semantic.core..", "vip.mate.semantic.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "org.apache.ibatis..", "com.baomidou.mybatisplus..",
                        "vip.mate.presales..", "vip.mate.bidding..", "vip.mate.delivery..")
                .check(classes);
    }
}

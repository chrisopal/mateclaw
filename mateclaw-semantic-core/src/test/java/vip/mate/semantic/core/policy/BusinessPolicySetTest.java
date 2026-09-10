package vip.mate.semantic.core.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessPolicySetTest {
    private static final String MEASUREMENT = "urn:test:Measurement";
    private static final String OBSERVED = "urn:test:observedValue";
    private static final String DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";

    @Test
    void outOfToleranceObservationRemainsValidBusinessInput() {
        var policy = new BusinessPolicySet("quality-v1", List.of(
                new BusinessPolicySet.Rule(MEASUREMENT, OBSERVED, true, "mm", Set.of())));
        var report = policy.validate(Set.of(MEASUREMENT),
                Map.of(OBSERVED, List.of(new BusinessPolicySet.Literal("0.026", DECIMAL, "mm"))), true);
        assertTrue(report.valid());
        assertEquals(NumericComparison.Outcome.ABOVE,
                NumericComparison.compare(new BigDecimal("0.026"), "mm", null, new BigDecimal("0.010"), "mm"));
    }

    @Test
    void missingPropertyIsNotAnOwlViolationAndOnlyCompleteSubmissionRequiresIt() {
        var policy = new BusinessPolicySet("v1", List.of(
                new BusinessPolicySet.Rule(MEASUREMENT, OBSERVED, true, "mm", Set.of())));
        assertTrue(policy.validate(Set.of(MEASUREMENT), Map.of(), false).valid());
        assertEquals("BUSINESS_REQUIRED", policy.validate(Set.of(MEASUREMENT), Map.of(), true)
                .violations().getFirst().code());
        assertTrue(policy.validate(Set.of("urn:test:InventoryItem"), Map.of(), true).valid());
    }

    @Test
    void unitAndEnumerationPoliciesAreExplicitAndScopedToAllApplicableTypes() {
        var policy = new BusinessPolicySet("v1", List.of(
                new BusinessPolicySet.Rule(MEASUREMENT, OBSERVED, false, "mm", Set.of("1", "2"))));
        var result = policy.validate(Set.of("urn:test:Thing", MEASUREMENT),
                Map.of(OBSERVED, List.of(new BusinessPolicySet.Literal("3", DECIMAL, "cm"))), true);
        assertEquals(Set.of("BUSINESS_UNIT_MISMATCH", "BUSINESS_VALUE_NOT_ALLOWED"),
                result.violations().stream().map(v -> v.code()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void singleValueUsesLiteralValueSpaceInsteadOfLexicalSpelling() {
        var policy = new BusinessPolicySet("v1", List.of(
                new BusinessPolicySet.Rule(MEASUREMENT, OBSERVED, false, "mm", Set.of(), true)));
        var result = policy.validate(Set.of(MEASUREMENT),
                Map.of(OBSERVED, List.of(
                        new BusinessPolicySet.Literal("380", DECIMAL, "mm"),
                        new BusinessPolicySet.Literal("380.0", DECIMAL, "mm"))), true);
        assertTrue(result.valid());
        var conflict = policy.validate(Set.of(MEASUREMENT),
                Map.of(OBSERVED, List.of(
                        new BusinessPolicySet.Literal("380", DECIMAL, "mm"),
                        new BusinessPolicySet.Literal("381", DECIMAL, "mm"))), true);
        assertEquals("BUSINESS_SINGLE_VALUE", conflict.violations().getFirst().code());
    }

    @Test
    void comparisonDoesNotGuessMissingMeasurementOrConvertUnknownUnits() {
        assertEquals(NumericComparison.Outcome.UNKNOWN,
                NumericComparison.compare(null, "mm", null, BigDecimal.ONE, "mm"));
        assertEquals(NumericComparison.Outcome.INCOMPARABLE_UNIT,
                NumericComparison.compare(BigDecimal.ONE, "cm", null, BigDecimal.TEN, "mm"));
        assertEquals(NumericComparison.Outcome.WITHIN,
                NumericComparison.compare(BigDecimal.ONE, "mm", BigDecimal.ONE, BigDecimal.ONE, "mm"));
        assertThrows(IllegalArgumentException.class, () ->
                NumericComparison.compare(BigDecimal.ONE, "mm", BigDecimal.TEN, BigDecimal.ONE, "mm"));
    }

    @Test
    void policyIdentityAndIriCannotBeEmptyOrRelative() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessPolicySet("", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new BusinessPolicySet.Rule(MEASUREMENT, "old-key", false, null, Set.of()));
    }
}

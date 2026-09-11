package vip.mate.semantic.ontology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.statement.CommandRecordRow;
import vip.mate.semantic.statement.repository.CommandRecordMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.OntologyDtos;
import vip.mate.semantic.web.OntologyDtos.BusinessPolicyCheckView;
import vip.mate.semantic.web.OntologyDtos.CheckBusinessPolicySample;
import vip.mate.semantic.web.OntologyDtos.DraftView;
import vip.mate.semantic.web.OntologyDtos.SaveBusinessPolicy;
import vip.mate.semantic.web.SemanticApiException;

/**
 * Owns business-policy authoring and draft-only sample checks.
 *
 * <p>The policy is metadata of the authoritative OWL document. This service deliberately does
 * not create a graph, entity, fact, or completion record while checking a sample.
 */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class BusinessPolicyService {
    private static final int MAX_RULES = 200;
    private static final int MAX_ENUM_VALUES = 1_000;
    private static final int MAX_ENUM_VALUE_LENGTH = 1_024;
    private static final int MAX_PROPERTIES = 200;
    private static final int MAX_VALUES_PER_PROPERTY = 100;
    private static final int MAX_LITERAL_LENGTH = 4_096;
    private static final Set<String> XSD_DATATYPES = Set.of(
            "string", "boolean", "decimal", "integer", "nonPositiveInteger", "negativeInteger", "long", "int",
            "short", "byte", "nonNegativeInteger", "positiveInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
            "double", "float", "dateTime", "dateTimeStamp", "date", "time", "duration", "hexBinary", "base64Binary",
            "anyURI", "normalizedString", "token", "language", "Name", "NCName", "NMTOKEN");

    private final OntologyMapper mapper;
    private final CommandRecordMapper commands;
    private final SemanticAccessService access;
    private final OntologyWireMapper wire;

    public BusinessPolicyService(OntologyMapper mapper, CommandRecordMapper commands,
            SemanticAccessService access, OntologyWireMapper wire) {
        this.mapper = Objects.requireNonNull(mapper);
        this.commands = Objects.requireNonNull(commands);
        this.access = Objects.requireNonNull(access);
        this.wire = Objects.requireNonNull(wire);
    }

    @Transactional
    public DraftView save(String scope, String ontologyId, SaveBusinessPolicy request) {
        access.require(scope, "member");
        validateOperation(request == null ? null : request.operationId());
        long workspace = workspace(scope);
        OntologyRow parent = requireParent(scope, ontologyId, true);
        String requestHash = hash(wire.encode(request));
        CommandRecordRow previous = commands.find(workspace, request.operationId());
        if (previous != null) {
            if (!"SAVE_BUSINESS_POLICY".equals(previous.getKind())
                    || !ontologyId.equals(previous.getResourceId())
                    || !requestHash.equals(previous.getPayloadHash())) {
                throw conflict("OPERATION_CONFLICT", "Operation id already used with different payload");
            }
            return wire.decode(previous.getResultJson(), DraftView.class);
        }

        OntologyRevisionRow row = requireDraft(parent);
        checkDraftVersion(row, request.expectedDraftVersion());
        BusinessPolicySet existing = wire.policy(row);
        BusinessPolicySet policy = parsePolicy(request.rules(), existing, row);
        if (sameRules(existing, policy)) {
            DraftView result = wire.draft(row);
            record(parent, request.operationId(), requestHash, result);
            return result;
        }

        OntologyDtos.DocumentView current = wire.document(row);
        OntologyDtos.DocumentInput input = new OntologyDtos.DocumentInput(
                current.source().modelSchema(), current.source().syntax(), current.source().documentText(),
                current.source().imports(), policy);
        wire.store(row, input);
        if (mapper.saveDraft(row, request.expectedDraftVersion()) != 1) {
            throw conflict("DRAFT_CONFLICT", "Draft has changed; reload persisted content");
        }
        row.setDraftVersion(Math.incrementExact(row.getDraftVersion()));
        parent.setDraftCounter(row.getDraftVersion());
        parent.setUpdatedAt(now());
        mapper.updateParent(parent);
        DraftView result = wire.draft(row);
        record(parent, request.operationId(), requestHash, result);
        return result;
    }

    @Transactional
    public BusinessPolicyCheckView checkSample(String scope, String ontologyId,
            CheckBusinessPolicySample request) {
        access.require(scope, "member");
        validateSampleRequest(request);
        // Lock the parent while pinning the sample to one draft snapshot. The check itself still
        // only reads the document and never creates graph state.
        OntologyRow parent = requireParent(scope, ontologyId, true);
        OntologyRevisionRow row = requireDraft(parent);
        checkDraftVersion(row, request.expectedDraftVersion());
        ParsedOntologyDocument parsed = wire.parsed(row);
        Set<String> classes = wire.classIris(parsed);
        if (!classes.contains(request.classIri())) {
            throw policyError("POLICY_CLASS_NOT_FOUND", "classIri", "Class is not present in the current draft");
        }
        Map<String, List<String>> termKinds = wire.termKinds(row);
        Map<String, List<BusinessPolicySet.Literal>> properties = request.properties();
        validateSampleTerms(properties, termKinds);
        BusinessPolicySet policy = wire.policy(row);
        var report = policy.validate(Set.of(request.classIri()), properties, request.completeSubmission());
        List<OntologyDtos.Violation> violations = report.violations().stream()
                .map(value -> new OntologyDtos.Violation(value.code(), value.path(), value.message(), value.severity().name()))
                .toList();
        return new BusinessPolicyCheckView(row.getDraftVersion(), policy.version(), report.valid(), violations);
    }

    private BusinessPolicySet parsePolicy(List<BusinessPolicySet.Rule> rules,
            BusinessPolicySet existing, OntologyRevisionRow row) {
        if (rules == null || rules.size() > MAX_RULES) {
            throw policyError("POLICY_RULES_INVALID", "rules", "Between zero and " + MAX_RULES + " rules are allowed");
        }
        final BusinessPolicySet policy;
        try {
            String version = sameRuleList(existing.rules(), rules)
                    ? existing.version()
                    : "policy-" + hash(canonicalRules(rules)).substring(0, 24);
            policy = new BusinessPolicySet(version, rules);
        } catch (RuntimeException exception) {
            throw policyError("POLICY_RULES_INVALID", "rules", "Business policy rules are invalid");
        }
        ParsedOntologyDocument parsed = wire.parsed(row);
        Set<String> classes = wire.classIris(parsed);
        Map<String, List<String>> termKinds = wire.termKinds(row);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < policy.rules().size(); index++) {
            BusinessPolicySet.Rule rule = policy.rules().get(index);
            String path = "rules[" + index + "]";
            if (!classes.contains(rule.classIri())) {
                throw policyError("POLICY_CLASS_NOT_FOUND", path + ".classIri", "Class is not present in the current draft");
            }
            List<String> kinds = termKinds.get(rule.predicateIri());
            if (kinds == null || kinds.stream().noneMatch(kind -> "DataProperty".equalsIgnoreCase(kind))) {
                throw policyError("POLICY_DATA_PROPERTY_NOT_FOUND", path + ".predicateIri",
                        "Predicate must be a data property in the current draft");
            }
            if (!seen.add(rule.classIri() + "\u0000" + rule.predicateIri())) {
                throw policyError("POLICY_DUPLICATE_RULE", path, "Only one rule per class and data property is allowed");
            }
            if (rule.allowedLexicalValues().size() > MAX_ENUM_VALUES) {
                throw policyError("POLICY_ENUM_TOO_LARGE", path + ".allowedLexicalValues",
                        "Enumeration exceeds " + MAX_ENUM_VALUES + " values");
            }
            for (String value : rule.allowedLexicalValues()) {
                if (value == null || value.isBlank() || value.length() > MAX_ENUM_VALUE_LENGTH) {
                    throw policyError("POLICY_ENUM_VALUE_INVALID", path + ".allowedLexicalValues",
                            "Enumeration values must be non-blank and at most " + MAX_ENUM_VALUE_LENGTH + " characters");
                }
            }
        }
        return policy;
    }

    private void validateSampleTerms(Map<String, List<BusinessPolicySet.Literal>> properties,
            Map<String, List<String>> termKinds) {
        for (Map.Entry<String, List<BusinessPolicySet.Literal>> entry : properties.entrySet()) {
            String predicate = entry.getKey();
            List<String> kinds = termKinds.get(predicate);
            if (predicate == null || kinds == null
                    || kinds.stream().noneMatch(kind -> "DataProperty".equalsIgnoreCase(kind))) {
                throw policyError("POLICY_DATA_PROPERTY_NOT_FOUND", "properties." + predicate,
                        "Property is not a data property in the current draft");
            }
            List<BusinessPolicySet.Literal> values = entry.getValue();
            if (values == null || values.size() > MAX_VALUES_PER_PROPERTY) {
                throw policyError("SAMPLE_VALUES_TOO_LARGE", "properties." + predicate,
                        "At most " + MAX_VALUES_PER_PROPERTY + " values are allowed per property");
            }
            for (int index = 0; index < values.size(); index++) {
                BusinessPolicySet.Literal value = values.get(index);
                if (value == null || value.lexicalValue().isBlank() || value.lexicalValue().length() > MAX_LITERAL_LENGTH) {
                    throw policyError("SAMPLE_LITERAL_INVALID", "properties." + predicate + "[" + index + "]",
                            "Sample lexical values must be non-blank and at most " + MAX_LITERAL_LENGTH + " characters");
                }
                if (!knownDatatype(value.datatypeIri(), termKinds)) {
                    throw policyError("POLICY_DATATYPE_NOT_FOUND", "properties." + predicate + "[" + index + "].datatypeIri",
                            "Datatype is not present in the current draft");
                }
            }
        }
    }

    private static boolean knownDatatype(String iri, Map<String, List<String>> termKinds) {
        if (iri == null) return false;
        // The parsed document is authoritative for declared/imported datatypes, including XSD.
        if (termKinds.getOrDefault(iri, List.of()).stream()
                .anyMatch(kind -> "Datatype".equalsIgnoreCase(kind))) return true;
        if (!iri.startsWith("http://www.w3.org/2001/XMLSchema#")) return false;
        return XSD_DATATYPES.contains(iri.substring("http://www.w3.org/2001/XMLSchema#".length()));
    }

    private static boolean sameRules(BusinessPolicySet left, BusinessPolicySet right) {
        return sameRuleList(left.rules(), right.rules());
    }

    private static boolean sameRuleList(List<BusinessPolicySet.Rule> left, List<BusinessPolicySet.Rule> right) {
        return canonicalRules(left).equals(canonicalRules(right));
    }

    private static String canonicalRules(List<BusinessPolicySet.Rule> rules) {
        // Length-prefix every text field so values such as ["a,b"] and ["a", "b"]
        // cannot collapse to the same policy identity.
        return rules.stream().map(rule -> field(rule.classIri()) + field(rule.predicateIri())
                + rule.required() + field(rule.unit()) + rule.singleValue()
                + rule.allowedLexicalValues().stream().sorted().map(BusinessPolicyService::field)
                        .collect(Collectors.joining()))
                .sorted().collect(Collectors.joining("\n"));
    }

    private static String field(String value) {
        return value == null ? "-" : value.length() + ":" + value;
    }

    private void validateSampleRequest(CheckBusinessPolicySample request) {
        if (request == null || request.expectedDraftVersion() == null || request.expectedDraftVersion() < 1
                || request.classIri() == null || request.classIri().isBlank() || request.classIri().length() > 2048
                || request.completeSubmission() == null || request.properties() == null
                || request.properties().size() > MAX_PROPERTIES) {
            throw new SemanticApiException(400, "INVALID_REQUEST", "Draft version, class, submission mode, and bounded properties are required");
        }
    }

    private static void validateOperation(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128) {
            throw new SemanticApiException(400, "INVALID_REQUEST", "operationId is required");
        }
    }

    private OntologyRow requireParent(String scope, String id, boolean lock) {
        OntologyRow row = lock ? mapper.lock(id, workspace(scope)) : mapper.find(id, workspace(scope));
        if (row == null) throw new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace");
        return row;
    }

    private OntologyRevisionRow requireDraft(OntologyRow parent) {
        if (parent.getDraftId() == null) throw new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace");
        OntologyRevisionRow row = mapper.revision(parent.getDraftId(), parent.getId());
        if (row == null || !"DRAFT".equals(row.getRevisionState()))
            throw new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace");
        return row;
    }

    private static void checkDraftVersion(OntologyRevisionRow row, Long expected) {
        if (expected == null || expected < 1) throw new SemanticApiException(400, "INVALID_REQUEST", "Positive expectedDraftVersion required");
        if (!expected.equals(row.getDraftVersion())) throw conflict("DRAFT_CONFLICT", "Draft has changed; reload persisted content");
    }

    private void record(OntologyRow parent, String operationId, String requestHash, DraftView result) {
        CommandRecordRow row = new CommandRecordRow();
        row.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr());
        row.setWorkspaceId(parent.getWorkspaceId());
        row.setOperationId(operationId);
        row.setKind("SAVE_BUSINESS_POLICY");
        row.setResourceId(parent.getId());
        row.setPayloadHash(requestHash);
        row.setResultJson(wire.encode(result));
        row.setCreatedAt(now());
        commands.insert(row);
    }

    private static long workspace(String scope) {
        try {
            long value = Long.parseLong(scope);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw new SemanticApiException(400, "WORKSPACE_REQUIRED", "Explicit X-Workspace-Id required");
        }
    }

    private static SemanticApiException policyError(String code, String path, String message) {
        return new SemanticApiException(422, code, message,
                List.of(new OntologyDtos.Violation(code, path, message, "ERROR")));
    }

    private static SemanticApiException conflict(String code, String message) {
        return new SemanticApiException(409, code, message);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.CommandRecordRow;
import vip.mate.semantic.statement.repository.*;
import vip.mate.semantic.web.*;
import vip.mate.semantic.web.OntologyPackageDtos.*;
import vip.mate.semantic.web.OntologyPackageDtos.Package;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyPackageService {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final String KIND = "IMPORT_ONTOLOGY_PACKAGE";
    private final ObjectMapper json =
            new ObjectMapper(
                    JsonFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(32)
                                            .maxStringLength(MAX_BYTES)
                                            .maxNumberLength(100)
                                            .build())
                            .build()).findAndRegisterModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SemanticAccessService access;
    private final OntologyApplicationService ontologies;
    private final OntologyWireMapper wire;
    private final CommandRecordMapper commands;
    private final GovernanceRecordMapper governance;
    private final JdbcTemplate jdbc;

    public OntologyPackageService(
            SemanticAccessService access,
            OntologyApplicationService ontologies,
            OntologyWireMapper wire,
            CommandRecordMapper commands,
            GovernanceRecordMapper governance,
            JdbcTemplate jdbc) {
        json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        json.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        json.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        this.access = access;
        this.ontologies = ontologies;
        this.wire = wire;
        this.commands = commands;
        this.governance = governance;
        this.jdbc = jdbc;
    }

    public Package export(String scope, String id, String revisionId) {
        var revision = ontologies.revision(scope, id, revisionId);
        return new Package(
                2,
                revision.name(),
                revision.description(),
                new Source(revision.name(), revision.version()),
                revision.document().source());
    }

    public Preview preview(String scope, byte[] body) {
        access.require(scope, "member");
        return preview(parse(body, Package.class));
    }

    private Preview preview(Package value) {
        if (value == null || !Integer.valueOf(2).equals(value.packageFormatVersion()))
            throw bad("Unsupported package format");
        wire.metadata(value.name(), value.description());
        if (value.source() != null) {
            wire.metadata(value.source().ontologyName(), "");
            if (value.source().version() == null || value.source().version() < 1)
                throw bad("Invalid source version");
        }
        var violations = wire.violations(value.document());
        return new Preview(
                packageDigest(value),
                value.name(),
                wire.parse("preview", "preview", value.document()).axioms().size(),
                value.document().imports().size(),
                violations);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ImportResult importPackage(String scope, byte[] body) {
        var actor = access.require(scope, "member");
        ImportRequest request = parse(body, ImportRequest.class);
        if (request == null) throw bad("Import request required");
        var preview = preview(request.content());
        wire.reject(preview.violations());
        wire.metadata(request.name(), request.content().description());
        if (request.operationId() == null
                || request.operationId().isBlank()
                || request.operationId().length() > 128)
            throw bad("operationId required, maximum 128 characters");
        if (!preview.digest().equals(request.expectedDigest()))
            throw conflict("PACKAGE_CHANGED", "Package changed; preview it again");
        String hash = digest(json.valueToTree(List.of(preview.digest(), request.name())));
        // A short workspace lock serializes imports with the same operation ID, including their
        // first insert.
        jdbc.queryForObject(
                "SELECT id FROM mate_workspace WHERE id=? AND deleted=0 FOR UPDATE",
                Long.class,
                Long.valueOf(scope));
        var previous = commands.find(Long.parseLong(scope), request.operationId());
        if (previous != null) {
            if (!KIND.equals(previous.getKind()) || !hash.equals(previous.getPayloadHash()))
                throw conflict("OPERATION_CONFLICT", "Operation belongs to a different request");
            return authorizedResult(scope, actor.getId().toString(), previous);
        }
        var ontology =
                ontologies.create(
                        scope,
                        new OntologyDtos.Metadata(request.name(), request.content().description()));
        var initial =
                ontologies.createDraft(scope, ontology.id(), new OntologyDtos.CreateDraft(null));
        var draft =
                ontologies.saveDraft(
                        scope,
                        ontology.id(),
                        new OntologyDtos.SaveDraft(
                                initial.draftVersion(),
                                request.name(),
                                request.content().description(),
                                request.content().document(), "import-save:" + digest(json.valueToTree(request.operationId()))));
        var result = new ImportResult(request.operationId(), ontology.id(), draft);
        var command = new CommandRecordRow();
        command.setId(id());
        command.setWorkspaceId(Long.valueOf(scope));
        command.setOperationId(request.operationId());
        command.setKind(KIND);
        command.setResourceId(ontology.id());
        command.setPayloadHash(hash);
        command.setResultJson(wire.encode(new StoredImport(actor.getId().toString(), result)));
        command.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        commands.insert(command);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operationId", request.operationId());
        detail.put("packageDigest", preview.digest());
        if (request.content().source() != null) detail.put("source", request.content().source());
        governance.insert(
                id(),
                Long.parseLong(scope),
                ontology.id(),
                draft.id(),
                KIND,
                actor.getId().toString(),
                wire.encode(detail),
                command.getCreatedAt());
        return result;
    }

    public ImportResult operation(String scope, String operationId) {
        var actor = access.require(scope, "member");
        var row = commands.find(Long.parseLong(scope), operationId);
        if (row == null || !KIND.equals(row.getKind()))
            throw new SemanticApiException(404, "NOT_FOUND", "Import not found");
        return authorizedResult(scope, actor.getId().toString(), row);
    }

    private ImportResult authorizedResult(String scope, String actor, CommandRecordRow row) {
        var stored = wire.decode(row.getResultJson(), StoredImport.class);
        if (!actor.equals(stored.actorId())) access.require(scope, "admin");
        ontologies.get(scope, row.getResourceId());
        return stored.result();
    }

    private <T> T parse(byte[] body, Class<T> type) {
        if (body.length > MAX_BYTES)
            throw new SemanticApiException(413, "PACKAGE_TOO_LARGE", "Package exceeds 1 MiB");
        try {
            return json.readValue(body, type);
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Invalid package JSON or fields");
        }
    }

    /**
     * Canonical key order, stable array order; digest is integrity metadata, never an authenticity
     * claim.
     */
    private String packageDigest(Package value) {
        return digest(json.valueToTree(value));
    }

    public static String documentDigest(OntologyDtos.DocumentInput document) {
        return digest(new ObjectMapper().findAndRegisterModules().valueToTree(document));
    }

    public static String digest(JsonNode node) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            canonical(node)
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            keys.forEach(k -> result.set(k, canonical(node.get(k))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(v -> result.add(canonical(v)));
            return result;
        }
        return node;
    }

    private static String id() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    private static SemanticApiException bad(String message) {
        return new SemanticApiException(400, "INVALID_PACKAGE", message);
    }

    private static SemanticApiException conflict(String code, String message) {
        return new SemanticApiException(409, code, message);
    }
}

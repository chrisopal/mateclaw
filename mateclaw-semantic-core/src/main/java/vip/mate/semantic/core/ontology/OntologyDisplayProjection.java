package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

/**
 * A bounded, read-only view of an OWL document for navigation and inspection.
 * It is derived from the authoritative document and is never an alternate
 * serialization of that document.
 */
public record OntologyDisplayProjection(
        String schemaVersion,
        String documentDigest,
        String importLockDigest,
        List<Node> nodes,
        List<Edge> edges,
        List<AxiomRef> axiomRefs,
        List<Expression> expressions,
        Coverage coverage) {

    public static final String SCHEMA_VERSION = "ontology-display-v1";

    public OntologyDisplayProjection {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        documentDigest = requireText(documentDigest, "documentDigest");
        importLockDigest = requireText(importLockDigest, "importLockDigest");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        axiomRefs = List.copyOf(Objects.requireNonNull(axiomRefs, "axiomRefs"));
        expressions = List.copyOf(Objects.requireNonNull(expressions, "expressions"));
        coverage = Objects.requireNonNull(coverage, "coverage");
    }

    /** Compatibility constructor for callers that only consume the first batch graph. */
    public OntologyDisplayProjection(
            String schemaVersion,
            String documentDigest,
            String importLockDigest,
            List<Node> nodes,
            List<Edge> edges,
            List<AxiomRef> axiomRefs,
            Coverage coverage) {
        this(schemaVersion, documentDigest, importLockDigest, nodes, edges, axiomRefs, List.of(), coverage);
    }

    public record Node(
            String id,
            String iri,
            String kind,
            List<Label> labels,
            List<String> axiomIds,
            List<String> features,
            boolean imported) {
        public Node {
            id = requireText(id, "id");
            iri = requireText(iri, "iri");
            kind = requireText(kind, "kind");
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
            axiomIds = List.copyOf(Objects.requireNonNull(axiomIds, "axiomIds"));
            features = List.copyOf(Objects.requireNonNull(features, "features"));
        }
    }

    public record Label(String value, String language, String axiomId) {
        public Label {
            value = Objects.requireNonNull(value, "value");
            language = language == null ? "" : language;
            axiomId = requireText(axiomId, "axiomId");
        }
    }

    public record Edge(
            String id,
            String source,
            String target,
            String kind,
            String label,
            String axiomId) {
        public Edge {
            id = requireText(id, "id");
            source = requireText(source, "source");
            target = requireText(target, "target");
            kind = requireText(kind, "kind");
            label = label == null ? "" : label;
            axiomId = requireText(axiomId, "axiomId");
        }
    }

    public record AxiomRef(
            String id,
            String artifactId,
            String axiomId,
            boolean imported,
            String rendering,
            String axiomType,
            String status,
            String reason) {
        public AxiomRef {
            id = requireText(id, "id");
            artifactId = requireText(artifactId, "artifactId");
            axiomId = requireText(axiomId, "axiomId");
            rendering = requireText(rendering, "rendering");
            axiomType = requireText(axiomType, "axiomType");
            status = requireText(status, "status");
            reason = reason == null ? "" : reason;
        }
    }

    public record Expression(
            String id,
            String axiomId,
            String path,
            String operator,
            List<ExpressionOperand> operands) {
        public Expression {
            id = requireText(id, "id");
            axiomId = requireText(axiomId, "axiomId");
            path = requireText(path, "path");
            operator = requireText(operator, "operator");
            operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        }
    }

    public record ExpressionOperand(
            String role,
            int position,
            String targetId,
            String value) {
        public ExpressionOperand {
            role = requireText(role, "role");
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
        }
    }

    public record Coverage(
            int total,
            int returned,
            boolean truncated,
            String dependencyScope,
            int lockedImportCount) {
        public Coverage {
            if (total < 0 || returned < 0 || lockedImportCount < 0) {
                throw new IllegalArgumentException("coverage counts must not be negative");
            }
            dependencyScope = requireText(dependencyScope, "dependencyScope");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

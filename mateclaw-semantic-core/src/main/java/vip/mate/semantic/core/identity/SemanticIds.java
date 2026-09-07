package vip.mate.semantic.core.identity;

/** Strongly typed identifiers supplied by the host application. */
public final class SemanticIds {

    private SemanticIds() {
    }

    private static String requireId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("semantic id must be a positive integer string");
        }
        return value;
    }

    public record WorkspaceId(String value) {
        public WorkspaceId {
            value = requireId(value);
        }
    }

    public record KnowledgeBaseId(String value) {
        public KnowledgeBaseId {
            value = requireId(value);
        }
    }

    public record GraphId(String value) {
        public GraphId {
            value = requireId(value);
        }
    }

    public record OntologyId(String value) {
        public OntologyId {
            value = requireId(value);
        }
    }

    public record OntologyRevisionId(String value) {
        public OntologyRevisionId {
            value = requireId(value);
        }
    }

    public record EntityId(String value) {
        public EntityId {
            value = requireId(value);
        }
    }

    public record StatementId(String value) {
        public StatementId {
            value = requireId(value);
        }
    }

    public record ProposalId(String value) {
        public ProposalId {
            value = requireId(value);
        }
    }

    public record SnapshotId(String value) {
        public SnapshotId {
            value = requireId(value);
        }
    }

    public record EvidenceId(String value) {
        public EvidenceId {
            value = requireId(value);
        }
    }

    public record ConflictId(String value) {
        public ConflictId {
            value = requireId(value);
        }
    }
}

package vip.mate.semantic.source;

import org.springframework.stereotype.Service;
import vip.mate.semantic.source.repository.SourceGovernanceReadRepository;

/**
 * Read-only persisted withdrawal facts, independent of semantic feature enablement. This port does
 * not authorize source access or mutate graphs; callers must first check Workspace/source access.
 */
@Service
public class SourceGovernanceReadService {
    private final SourceGovernanceReadRepository repository;

    public SourceGovernanceReadService(SourceGovernanceReadRepository repository) {
        this.repository = repository;
    }

    /** Caller must authorize Workspace, frozen materials and the returned current source. */
    public java.util.Optional<String> availableEvidenceSource(
            String scope, String graphId, String kbId, String evidenceId) {
        return repository.availableEvidenceSource(scope, graphId, kbId, evidenceId);
    }

    public boolean isWithdrawn(String graphId, String sourceId) {
        return repository.isWithdrawn(graphId, sourceId);
    }
}

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

    public boolean isWithdrawn(String graphId, String sourceId) {
        return repository.isWithdrawn(graphId, sourceId);
    }
}

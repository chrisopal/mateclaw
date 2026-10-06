package vip.mate.wiki.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import vip.mate.wiki.repository.WikiSourceReadRepository;

/**
 * Read-only current source facts scoped to the persisted Workspace. A successful read is not an
 * actor or employee access grant; callers must enforce their source authorization policy.
 */
@Service
public class WikiSourceReadService {
    private final WikiSourceReadRepository repository;

    public WikiSourceReadService(WikiSourceReadRepository repository) {
        this.repository = repository;
    }

    public Optional<CurrentSource> readInWorkspace(String workspaceId, String sourceId) {
        return repository
                .readInWorkspace(workspaceId, sourceId)
                .map(
                        row ->
                                new CurrentSource(
                                        row.sourceId(),
                                        row.kbId(),
                                        row.text() == null ? "" : row.text()));
    }

    public record CurrentSource(String sourceId, String kbId, String text) {}
}

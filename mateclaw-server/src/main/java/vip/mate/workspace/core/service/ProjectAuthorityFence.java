package vip.mate.workspace.core.service;

import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Locks existing execution-authority rows until a project result transaction completes. */
@Service
public class ProjectAuthorityFence {
    private final JdbcTemplate jdbc;

    public ProjectAuthorityFence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Source(String kbId, String rawId, String graphId) {}

    /**
     * A revocation that wins these row locks is visible to the caller's subsequent revalidation;
     * one that loses cannot commit until the result transaction finishes. The graph row coordinates
     * governance inserts, which have no existing governance row to lock.
     */
    public boolean lockForResult(
            String workspaceId,
            Collection<String> actorIds,
            String employeeId,
            String modelConfigId,
            Collection<Source> sources) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Result authority requires a transaction");
        long workspace = id(workspaceId);
        var actors = new TreeSet<Long>();
        for (String actor : actorIds) actors.add(id(actor));
        for (long actor : actors)
            if (!lock("SELECT id FROM mate_user WHERE id=? FOR UPDATE", actor)) return false;
        if (!lock("SELECT id FROM mate_workspace WHERE id=? FOR UPDATE", workspace)) return false;
        for (long actor : actors)
            jdbc.queryForList(
                    "SELECT id FROM mate_workspace_member WHERE workspace_id=? AND user_id=?"
                            + " AND deleted=0 FOR UPDATE",
                    workspace,
                    actor);
        if (!lock(
                "SELECT id FROM mate_agent WHERE id=? AND workspace_id=? FOR UPDATE",
                id(employeeId),
                workspace)) return false;
        if (!lock("SELECT id FROM mate_model_config WHERE id=? FOR UPDATE", id(modelConfigId)))
            return false;

        var knowledgeBases = new TreeSet<Long>();
        var rawMaterials = new TreeMap<Long, Long>();
        Set<String> graphs = new TreeSet<>();
        for (Source source : sources) {
            long kb = id(source.kbId());
            knowledgeBases.add(kb);
            rawMaterials.put(id(source.rawId()), kb);
            if (source.graphId() != null && !source.graphId().isBlank())
                graphs.add(source.graphId());
        }
        for (long kb : knowledgeBases)
            if (!lock(
                    "SELECT id FROM mate_wiki_knowledge_base WHERE id=? AND workspace_id=?"
                            + " FOR UPDATE",
                    kb,
                    workspace)) return false;
        for (var source : rawMaterials.entrySet())
            if (!lock(
                    "SELECT id FROM mate_wiki_raw_material WHERE id=? AND kb_id=? FOR UPDATE",
                    source.getKey(),
                    source.getValue())) return false;
        for (String graph : graphs)
            if (!lock(
                    "SELECT id FROM mate_semantic_graph WHERE id=? AND workspace_id=? FOR UPDATE",
                    graph,
                    workspace)) return false;
        return true;
    }

    private boolean lock(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Invalid execution authority identifier");
    }
}

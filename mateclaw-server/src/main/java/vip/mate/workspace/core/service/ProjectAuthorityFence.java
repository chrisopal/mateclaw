package vip.mate.workspace.core.service;

import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Locks existing execution-authority rows until a project result transaction completes. */
@Service
public class ProjectAuthorityFence {
    private final JdbcTemplate jdbc;
    private final SqlSessionTemplate sessions;

    public ProjectAuthorityFence(JdbcTemplate jdbc, SqlSessionTemplate sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
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
        requireTransaction();
        long workspace = id(workspaceId);
        if (!lockActors(workspace, actorIds)) return false;
        if (!lockEmployee(workspace, employeeId)) return false;
        if (!lock("SELECT id FROM mate_model_config WHERE id=? FOR UPDATE", id(modelConfigId)))
            return false;
        return lockSources(workspace, java.util.List.of(), java.util.List.of(), sources);
    }

    /**
     * Holds authority for a human command through its acceptance transaction. An unbound employee
     * is legitimate; model authority is not part of a human command. The caller must revalidate
     * current roles, bindings and source access after this returns, before writing any result. Use
     * READ_COMMITTED so revalidation observes authority committed before these locks; this helper
     * does not change the caller's transaction isolation or authorize missing memberships.
     */
    public boolean lockForCommand(
            String workspaceId,
            Collection<String> actorIds,
            String employeeId,
            Collection<String> knowledgeBaseIds,
            Collection<String> graphIds,
            Collection<Source> sources) {
        requireTransaction();
        if (actorIds.isEmpty())
            throw new IllegalArgumentException("Command authority requires an actor");
        long workspace = id(workspaceId);
        if (!lockActors(workspace, actorIds)) return false;
        if (employeeId != null && !employeeId.isBlank() && !lockEmployee(workspace, employeeId))
            return false;
        return lockSources(workspace, knowledgeBaseIds, graphIds, sources);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Result authority requires a transaction");
    }

    private boolean lockActors(long workspace, Collection<String> actorIds) {
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
        return true;
    }

    private boolean lockEmployee(long workspace, String employeeId) {
        return lock(
                "SELECT id FROM mate_agent WHERE id=? AND workspace_id=? FOR UPDATE",
                id(employeeId),
                workspace);
    }

    private boolean lockSources(
            long workspace,
            Collection<String> knowledgeBaseIds,
            Collection<String> graphIds,
            Collection<Source> sources) {
        var knowledgeBases = new TreeSet<Long>();
        for (String kb : knowledgeBaseIds) knowledgeBases.add(id(kb));
        var rawMaterials = new TreeMap<Long, Long>();
        Set<String> graphs = new TreeSet<>();
        for (String graph : graphIds) {
            if (graph == null || graph.isBlank())
                throw new IllegalArgumentException("Invalid command graph identifier");
            graphs.add(graph);
        }
        for (Source source : sources) {
            long kb = id(source.kbId());
            knowledgeBases.add(kb);
            rawMaterials.put(id(source.rawId()), kb);
            if (source.graphId() != null && !source.graphId().isBlank())
                graphs.add(source.graphId());
        }
        for (String graph : graphs)
            if (!lock(
                    "SELECT id FROM mate_semantic_graph WHERE id=? AND workspace_id=? FOR UPDATE",
                    graph,
                    workspace)) return false;
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
        // JDBC row locks do not invalidate MyBatis reads cached earlier in this transaction.
        // Revalidation must read the authority that won the locks, rather than that old snapshot.
        sessions.clearCache();
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

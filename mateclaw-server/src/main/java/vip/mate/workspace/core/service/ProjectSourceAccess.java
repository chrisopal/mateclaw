package vip.mate.workspace.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Workspace and employee KB visibility shared by project context and tool reads. */
@Service
public class ProjectSourceAccess {
    private final JdbcTemplate jdbc;

    public ProjectSourceAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean canEmployeeReadKb(String workspaceId, String employeeId, String kbId) {
        long workspace;
        long employee;
        long kb;
        try {
            workspace = positive(workspaceId);
            employee = positive(employeeId);
            kb = positive(kbId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (count(
                        "SELECT COUNT(*) FROM mate_agent WHERE id=? AND workspace_id=?"
                                + " AND enabled=TRUE AND deleted=0"
                                + " AND (wiki_disabled=FALSE OR wiki_disabled IS NULL)",
                        employee,
                        workspace)
                != 1) return false;
        if (count(
                        "SELECT COUNT(*) FROM mate_wiki_knowledge_base WHERE id=?"
                                + " AND workspace_id=? AND deleted=0",
                        kb,
                        workspace)
                != 1) return false;
        if (count(
                        "SELECT COUNT(*) FROM mate_agent_wiki_kb WHERE agent_id=? AND deleted=0",
                        employee)
                == 0) return true;
        return count(
                        "SELECT COUNT(*) FROM mate_agent_wiki_kb WHERE agent_id=? AND kb_id=?"
                                + " AND enabled=TRUE AND deleted=0",
                        employee,
                        kb)
                == 1;
    }

    /** Resolve raw source ownership from live storage, rather than trusting a caller snapshot. */
    public boolean canEmployeeReadSource(String workspaceId, String employeeId, String sourceId) {
        long source;
        try {
            source = positive(sourceId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        var kbIds =
                jdbc.queryForList(
                        "SELECT kb_id FROM mate_wiki_raw_material WHERE id=? AND deleted=0",
                        Long.class,
                        source);
        return kbIds.size() == 1
                && canEmployeeReadKb(workspaceId, employeeId, kbIds.getFirst().toString());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static long positive(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Invalid source scope identifier");
    }
}

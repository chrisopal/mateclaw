package vip.mate.wiki.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads current persisted source facts without extraction or actor authorization. */
@Repository
public class WikiSourceReadRepository {
    private final JdbcTemplate jdbc;

    public WikiSourceReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SourceRow> readInWorkspace(String workspaceId, String sourceId) {
        var rows =
                jdbc.query(
                        "SELECT r.id AS source_id,r.kb_id,COALESCE(NULLIF(r.extracted_text,''),r.original_content) AS source_text FROM"
                                + " mate_wiki_raw_material r JOIN mate_wiki_knowledge_base k ON k.id=r.kb_id WHERE"
                                + " r.id=? AND k.workspace_id=? AND r.deleted=0 AND k.deleted=0",
                        (rs, rowNum) ->
                                new SourceRow(
                                        rs.getString("source_id"),
                                        rs.getString("kb_id"),
                                        rs.getString("source_text")),
                        sourceId,
                        workspaceId);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    public record SourceRow(String sourceId, String kbId, String text) {}
}

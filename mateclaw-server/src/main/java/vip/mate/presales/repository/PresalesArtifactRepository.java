package vip.mate.presales.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stored facts only. Callers retain authorization, integrity checks and transactions. */
@Repository
public class PresalesArtifactRepository {
    private final JdbcTemplate jdbc;

    public PresalesArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredArtifact(String digest, String contentBase64) {}

    public void insert(
            String projectId,
            String releaseId,
            String filename,
            String digest,
            String contentBase64) {
        jdbc.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,?,?,?)",
                projectId,
                releaseId,
                filename,
                digest,
                contentBase64);
    }

    public int reassignRelease(String projectId, String releaseId, String storedReleaseId) {
        return jdbc.update(
                "UPDATE mate_presales_artifact SET release_id=? WHERE project_id=? AND release_id=?",
                storedReleaseId,
                projectId,
                releaseId);
    }

    public List<StoredArtifact> find(String projectId, String releaseId, String filename) {
        return jdbc.query(
                "SELECT digest,content_base64 FROM mate_presales_artifact WHERE project_id=? AND release_id=? AND filename=?",
                (row, n) -> new StoredArtifact(row.getString(1), row.getString(2)),
                projectId,
                releaseId,
                filename);
    }
}

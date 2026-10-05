package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesArtifactRepository.StoredArtifact;

/** Reads stored bytes after the caller's source/publication checks; never renders or writes. */
final class PresalesArtifactReader {
    private final PresalesArtifactRepository repository;

    PresalesArtifactReader(PresalesArtifactRepository repository) {
        this.repository = repository;
    }

    void verifyCandidate(String projectId, ObjectNode release) {
        for (var file : release.path("files")) {
            var rows =
                    repository.find(
                            projectId, release.path("id").asText(), file.path("filename").asText());
            if (rows.size() != 1)
                throw new PresalesRejected(409, "ARTIFACT_MISSING", "Candidate file missing");
            verifiedBytes(
                    rows.getFirst(),
                    file.path("sha256").asText(),
                    false,
                    "Candidate bytes changed");
        }
    }

    byte[] releaseFile(String projectId, ObjectNode release, String filename) {
        JsonNode selected = null;
        for (var file : release.path("files")) {
            if (!filename.equals(file.path("filename").asText())) continue;
            if (selected != null)
                throw new PresalesRejected(409, "ARTIFACT_MISSING", "Published file is ambiguous");
            selected = file;
        }
        if (selected == null) throw new PresalesRejected(404, "NOT_FOUND", "Artifact not found");
        var rows = repository.find(projectId, release.path("id").asText(), filename);
        if (rows.isEmpty()) throw new PresalesRejected(404, "NOT_FOUND", "Artifact not found");
        if (rows.size() != 1)
            throw new PresalesRejected(409, "ARTIFACT_MISSING", "Published file is ambiguous");
        return verifiedBytes(
                rows.getFirst(),
                selected.path("sha256").asText(),
                false,
                "Artifact integrity failure");
    }

    byte[] presentation(
            String projectId, String artifactId, String filename, String expectedDigest) {
        var rows = repository.find(projectId, artifactId, filename);
        if (rows.size() != 1)
            throw new PresalesRejected(404, "NOT_FOUND", "Presentation artifact not found");
        return verifiedBytes(
                rows.getFirst(), expectedDigest, true, "Presentation artifact integrity failure");
    }

    boolean presentationFile(ObjectNode solution, String filename) {
        if ("solution.pptx".equals(filename)) return true;
        for (var slide : solution.path("presentation").path("slides"))
            if (filename.equals(slide.path("filename").asText())) return true;
        return "quality-report.json".equals(filename);
    }

    String presentationDigest(ObjectNode solution, String filename) {
        if ("solution.pptx".equals(filename))
            return solution.path("presentation").path("sha256").asText();
        if ("quality-report.json".equals(filename))
            return solution.path("presentation").path("qualityReportSha256").asText();
        for (var slide : solution.path("presentation").path("slides"))
            if (filename.equals(slide.path("filename").asText()))
                return slide.path("sha256").asText();
        return "";
    }

    private static byte[] verifiedBytes(
            StoredArtifact row, String expectedDigest, boolean allowEmptyExpected, String message) {
        byte[] bytes = Base64.getDecoder().decode(row.contentBase64());
        String digest = PresalesArtifactRenderer.digest(bytes);
        if (!digest.equals(row.digest())
                || ((!allowEmptyExpected || !expectedDigest.isBlank())
                        && !digest.equals(expectedDigest)))
            throw new PresalesRejected(409, "ARTIFACT_DIGEST_MISMATCH", message);
        return bytes;
    }
}

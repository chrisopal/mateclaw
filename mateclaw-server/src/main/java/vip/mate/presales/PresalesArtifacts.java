package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesArtifactRepository.StoredArtifact;

/**
 * Materializes and verifies artifacts; callers retain authorization, lifecycle and transactions.
 */
final class PresalesArtifacts {
    private final PresalesArtifactRepository repository;
    private final PresalesArtifactRenderer renderer;

    PresalesArtifacts(PresalesArtifactRepository repository, PresalesArtifactRenderer renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }

    /** Serializable input captures actual PPT bytes, including legacy rows without a pin. */
    ObjectNode capture(String projectId, ObjectNode solution) {
        ObjectNode input = solution.objectNode();
        input.put("templateVersion", PresalesArtifactRenderer.TEMPLATE_VERSION);
        input.set("solution", solution.deepCopy());
        String presentationId = solution.path("presentation").path("artifactId").asText();
        if (!presentationId.isBlank()) {
            var rows = repository.findForUpdate(projectId, presentationId, "solution.pptx");
            if (rows.size() != 1)
                throw new PresalesRejected(404, "NOT_FOUND", "Presentation artifact not found");
            byte[] bytes =
                    verifiedBytes(
                            rows.getFirst(),
                            solution.path("presentation").path("sha256").asText(),
                            true,
                            "Presentation artifact integrity failure");
            input.put("presentationBytes", Base64.getEncoder().encodeToString(bytes));
            input.put("presentationDigest", PresalesArtifactRenderer.digest(bytes));
        }
        return input;
    }

    record RenderedFile(String filename, String digest, int size, String contentBase64) {}

    /** No repository calls: conversion, hashing and encoding all happen outside transactions. */
    List<RenderedFile> render(ObjectNode input) {
        if (!PresalesArtifactRenderer.TEMPLATE_VERSION.equals(
                input.path("templateVersion").asText()))
            throw new PresalesRejected(409, "RENDER_TEMPLATE_CHANGED", "Render template changed");
        ObjectNode solution = (ObjectNode) input.path("solution");
        var document = document(solution, solution.path("id").asText(), false);
        var files =
                new LinkedHashMap<>(
                        solution.path("presentation").path("artifactId").isTextual()
                                ? renderer.renderWithoutSlides(document)
                                : renderer.render(document));
        if (input.has("presentationBytes"))
            files.put(
                    "solution.pptx",
                    Base64.getDecoder().decode(input.path("presentationBytes").asText()));
        var result = new ArrayList<RenderedFile>();
        for (var entry : files.entrySet())
            result.add(
                    new RenderedFile(
                            entry.getKey(),
                            PresalesArtifactRenderer.digest(entry.getValue()),
                            entry.getValue().length,
                            Base64.getEncoder().encodeToString(entry.getValue())));
        return List.copyOf(result);
    }

    void storeCandidate(
            String projectId, String releaseId, List<RenderedFile> files, ObjectNode candidate) {
        var manifest = candidate.putArray("files");
        for (var file : files) {
            repository.insert(
                    projectId, releaseId, file.filename(), file.digest(), file.contentBase64());
            manifest.addObject()
                    .put("filename", file.filename())
                    .put("sha256", file.digest())
                    .put("size", file.size());
        }
    }

    void reassignCandidate(String projectId, String provisionalReleaseId, String storedReleaseId) {
        if (!provisionalReleaseId.equals(storedReleaseId))
            repository.reassignRelease(projectId, provisionalReleaseId, storedReleaseId);
    }

    ObjectNode handoff(String projectId, String releaseId, ObjectNode release) {
        verifyCandidate(projectId, release);
        JsonNode snapshot = release.path("handoffSnapshot");
        if (!snapshot.isObject())
            throw new PresalesRejected(409, "HISTORICAL_SNAPSHOT_UNAVAILABLE", "该历史发布缺少可验证的冻结快照");
        ObjectNode result = ((ObjectNode) snapshot).deepCopy();
        result.put("releaseId", releaseId);
        return result;
    }

    byte[] preview(String projectId, ObjectNode release, String filename) {
        verifyCandidate(projectId, release);
        return releaseFile(projectId, release, filename);
    }

    byte[] draft(String projectId, String solutionId, ObjectNode solution, String filename) {
        String presentationArtifact = solution.path("presentation").path("artifactId").asText();
        if (!presentationArtifact.isBlank() && presentationFile(solution, filename))
            return presentation(
                    projectId,
                    presentationArtifact,
                    filename,
                    presentationDigest(solution, filename));
        byte[] bytes = renderer.render(document(solution, solutionId, true)).get(filename);
        if (bytes == null) throw new PresalesRejected(404, "NOT_FOUND", "Unknown artifact format");
        return bytes;
    }

    private static PresalesArtifactRenderer.Document document(
            ObjectNode solution, String revision, boolean draft) {
        var sections = new ArrayList<PresalesArtifactRenderer.Section>();
        for (var section : solution.path("sections"))
            sections.add(
                    new PresalesArtifactRenderer.Section(
                            section.path("title").asText(), section.path("text").asText()));
        return new PresalesArtifactRenderer.Document(
                solution.path("title").asText(),
                revision,
                draft,
                sections,
                draft ? "UNAPPROVED DRAFT — internal review only" : "");
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

    private byte[] presentation(
            String projectId, String artifactId, String filename, String expectedDigest) {
        var rows = repository.find(projectId, artifactId, filename);
        if (rows.size() != 1)
            throw new PresalesRejected(404, "NOT_FOUND", "Presentation artifact not found");
        return verifiedBytes(
                rows.getFirst(), expectedDigest, true, "Presentation artifact integrity failure");
    }

    private boolean presentationFile(ObjectNode solution, String filename) {
        if ("solution.pptx".equals(filename)) return true;
        for (var slide : solution.path("presentation").path("slides"))
            if (filename.equals(slide.path("filename").asText())) return true;
        return "quality-report.json".equals(filename);
    }

    private String presentationDigest(ObjectNode solution, String filename) {
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

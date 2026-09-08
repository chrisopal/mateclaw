package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class OntologyPackageDtos {
    private OntologyPackageDtos() {}

    public record Source(String ontologyName, Integer version) {}

    public record Package(
            Integer packageFormatVersion,
            String name,
            String description,
            Source source,
            OntologyDtos.Definition definition) {}

    public record Preview(
            String digest,
            String name,
            int typeCount,
            int predicateCount,
            List<OntologyDtos.Violation> violations) {}

    public record ImportRequest(
            @JsonProperty("package") Package content,
            String expectedDigest,
            String operationId,
            String name) {}

    public record ImportResult(
            String operationId, String ontologyId, OntologyDtos.DraftView draft) {}

    public record StoredImport(String actorId, ImportResult result) {}
}

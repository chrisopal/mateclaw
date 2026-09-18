package vip.mate.presales;

import static vip.mate.presales.PresalesDtos.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestController
@RequestMapping("/api/v1/presales")
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesController {
  private final PresalesService service;

  public PresalesController(PresalesService service) {
    this.service = service;
  }

  @GetMapping("/capabilities")
  public R<?> capabilities(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope) {
    return R.ok(service.capabilities(scope));
  }

  @GetMapping("/projects")
  public R<?> list(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String ownerId,
      @RequestParam(required = false) String stage,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return R.ok(service.list(scope, q, status, ownerId, stage, page, pageSize));
  }

  @PostMapping("/projects")
  public R<?> create(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @RequestBody Create request) {
    return R.ok(service.create(scope, request));
  }

  @GetMapping("/projects/{id}")
  public R<?> get(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id) {
    return R.ok(service.get(scope, id));
  }

  @PostMapping("/projects/{id}/commands")
  public R<?> command(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @RequestBody Command request) {
    return R.ok(service.command(scope, id, request));
  }

  @PatchMapping("/projects/{id}")
  public R<?> update(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @RequestBody ObjectNode request) {
    return R.ok(
        service.command(
            scope,
            id,
            new Command(
                request.has("expectedVersion") ? request.path("expectedVersion").asInt() : null,
                request.path("operationId").asText(),
                "UPDATE_PROJECT",
                request)));
  }

  @GetMapping("/projects/{id}/evidence")
  public R<?> evidence(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @RequestParam String graphId,
      @RequestParam String evidenceId) {
    return R.ok(service.evidence(scope, id, graphId, evidenceId));
  }

  @GetMapping("/projects/{id}/releases/{releaseId}/files/{filename}")
  public org.springframework.http.ResponseEntity<byte[]> artifact(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @PathVariable String releaseId,
      @PathVariable String filename) {
    byte[] bytes = service.artifact(scope, id, releaseId, filename);
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
        .header("Cache-Control", "no-store")
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }

  @GetMapping("/projects/{id}/releases/{releaseId}/preview/{filename}")
  public org.springframework.http.ResponseEntity<byte[]> preview(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @PathVariable String releaseId,
      @PathVariable String filename) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "inline")
        .header("Cache-Control", "no-store")
        .header("X-Presales-Status", "UNAPPROVED-CANDIDATE")
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(service.preview(scope, id, releaseId, filename));
  }

  @GetMapping("/projects/{id}/solutions/{solutionId}/draft/{filename}")
  public org.springframework.http.ResponseEntity<byte[]> draft(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id,
      @PathVariable String solutionId,
      @PathVariable String filename) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"draft-" + filename + "\"")
        .header("Cache-Control", "no-store")
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(service.draftArtifact(scope, id, solutionId, filename));
  }

  @GetMapping("/projects/{id}/handoff")
  public R<?> handoff(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id) {
    return R.ok(service.handoff(scope, id));
  }

  @GetMapping("/sources")
  public R<?> sources(@RequestHeader(value = "X-Workspace-Id", required = false) String scope) {
    return R.ok(service.sources(scope));
  }

  @GetMapping("/projects/{id}/statements")
  public R<?> statements(
      @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
      @PathVariable String id) {
    return R.ok(service.trustedStatements(scope, id));
  }
}

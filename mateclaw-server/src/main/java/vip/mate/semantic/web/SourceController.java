package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.source.SourceApplicationService;
import vip.mate.semantic.web.SourceDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled", havingValue="true")
public class SourceController {
    private final SourceApplicationService service;
    public SourceController(SourceApplicationService service) { this.service=service; }
    @PostMapping("/imports") @RequireWorkspaceRole("member")
    public R<ImportJob> start(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId,@RequestBody ImportRequest request){ return R.ok(service.startImport(scope,graphId,request)); }
    @GetMapping("/imports/{jobId}") @RequireWorkspaceRole("viewer")
    public R<ImportJob> job(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId,@PathVariable String jobId){ return R.ok(service.readImport(scope,graphId,jobId)); }
    @PostMapping("/imports/{jobId}/retry") @RequireWorkspaceRole("member")
    public R<ImportJob> retry(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId,@PathVariable String jobId,@RequestBody RetryRequest request){ return R.ok(service.retryImport(scope,graphId,jobId,request)); }
    @GetMapping("/sources") @RequireWorkspaceRole("viewer")
    public R<Page<SourceView>> sources(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId){ return R.ok(service.sources(scope,graphId)); }
    @GetMapping("/snapshots") @RequireWorkspaceRole("viewer")
    public R<Page<Snapshot>> snapshots(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId){ return R.ok(service.snapshots(scope,graphId)); }
    @GetMapping("/snapshots/{snapshotId}/text") @RequireWorkspaceRole("member")
    public R<SnapshotText> text(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId,@PathVariable String snapshotId,@RequestParam(required=false) Integer startCodePoint,@RequestParam(required=false) Integer endCodePoint){ return R.ok(service.text(scope,graphId,snapshotId,startCodePoint,endCodePoint)); }
    @PostMapping("/snapshots/{snapshotId}/evidence") @RequireWorkspaceRole("member")
    public R<EvidenceView> evidence(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String graphId,@PathVariable String snapshotId,@RequestBody EvidenceRequest request){ return R.ok(service.createEvidence(scope,graphId,snapshotId,request)); }
}

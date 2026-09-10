package vip.mate.semantic.ontology.source;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import static vip.mate.semantic.ontology.source.OntologySourceDtos.*;

@RestController
@RequestMapping("/api/v1/semantic/ontologies/{ontologyId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class OntologySourceController {
    private final OntologySourceReviewService service;
    public OntologySourceController(OntologySourceReviewService service){this.service=service;}
    @PostMapping("/draft/axiom-sources") @RequireWorkspaceRole("member")
    public R<Bound> bind(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId,@RequestBody BindRequest request){return R.ok(service.bind(scope,ontologyId,request));}
    @GetMapping("/revisions/{revisionId}/axiom-sources") @RequireWorkspaceRole("viewer")
    public R<List<Binding>> bindings(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId,@PathVariable String revisionId){return R.ok(service.bindings(scope,ontologyId,revisionId));}
    @PostMapping("/source-reviews/scan") @RequireWorkspaceRole("member")
    public R<List<Review>> scan(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId,@RequestBody ScanRequest request){return R.ok(service.scan(scope,ontologyId,request));}
    @GetMapping("/source-reviews") @RequireWorkspaceRole("viewer")
    public R<List<Review>> reviews(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId){return R.ok(service.reviews(scope,ontologyId));}
    @GetMapping("/source-reviews/{reviewId}/snapshots") @RequireWorkspaceRole("viewer")
    public R<java.util.Map<String,Snapshot>> snapshots(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId,@PathVariable String reviewId){return R.ok(service.reviewSnapshots(scope,ontologyId,reviewId));}
    @PostMapping("/source-reviews/{reviewId}/decision") @RequireWorkspaceRole("admin")
    public R<Review> decide(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String ontologyId,@PathVariable String reviewId,@RequestBody DecideRequest request){return R.ok(service.decide(scope,ontologyId,reviewId,request));}
}

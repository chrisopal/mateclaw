package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestController
@RequestMapping("/api/v1/bidding")
@RequiredArgsConstructor
public class BiddingController {
    private final BiddingAccess access;
    private final BiddingProjectService projects;
    private final BiddingCommandService commands;

    @GetMapping("/capabilities")
    public R<?> capabilities(@RequestHeader("X-Workspace-Id") String workspace) {
        String actor=access.require(workspace,"viewer"); boolean canWrite=hasRole(workspace,"member");
        boolean canApprove=hasRole(workspace,"admin");
        return R.ok(java.util.Map.of("enabled",true,"canWrite",canWrite,"canApprove",canApprove));
    }
    @GetMapping("/projects")
    public R<?> list(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,
        @RequestParam(required=false) String name,@RequestParam(required=false) String stage,
        @RequestParam(required=false) String ownerId,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int pageSize) {
        String actor=access.require(workspace,"viewer");
        return R.ok(projects.list(new BiddingTypes.Scope(workspace,actor,null),name,stage,ownerId,page,pageSize));
    }
    @PostMapping("/projects")
    public R<?> create(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,
        @RequestBody BiddingTypes.NewProject request) {
        String actor=access.require(workspace,"member");
        return R.ok(projects.create(new BiddingTypes.Scope(workspace,actor,null),request));
    }
    @GetMapping("/projects/{id}")
    public R<?> get(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id) {
        String actor=access.require(workspace,"viewer");
        return R.ok(projects.get(new BiddingTypes.Scope(workspace,actor,id)));
    }
    @PostMapping("/projects/{id}/commands")
    public R<?> command(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id,
        @RequestBody BiddingTypes.Command command) {
        String actor=access.require(workspace,"member");
        return R.ok(commands.execute(new BiddingTypes.Scope(workspace,actor,id),command));
    }
    private boolean hasRole(String workspace,String role) { try { access.require(workspace,role); return true; } catch(BiddingApiException e) { if(e.status()==403) return false; throw e; } }
}

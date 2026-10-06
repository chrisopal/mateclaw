package vip.mate.delivery;

import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.presales.api.PresalesHandoffReader.Handoff;

@RestController
@RequestMapping("/api/v1/delivery")
public class DeliveryHandoffController {
    private final DeliveryHandoffService service;

    public DeliveryHandoffController(DeliveryHandoffService service) {
        this.service = service;
    }

    @GetMapping("/handoff-preview")
    public R<Handoff> preview(
            @RequestHeader("X-Workspace-Id") String workspace,
            @RequestParam String projectId,
            @RequestParam String releaseId) {
        return R.ok(service.preview(workspace, projectId, releaseId));
    }

    @PostMapping("/handoffs")
    public R<DeliveryHandoffService.Accepted> receive(
            @RequestHeader("X-Workspace-Id") String workspace,
            @RequestBody DeliveryHandoffService.Receive request) {
        return R.ok(service.receive(workspace, request));
    }

    @GetMapping("/handoffs/{id}")
    public R<DeliveryHandoffService.Accepted> get(
            @RequestHeader("X-Workspace-Id") String workspace, @PathVariable String id) {
        return R.ok(service.get(workspace, id));
    }
}

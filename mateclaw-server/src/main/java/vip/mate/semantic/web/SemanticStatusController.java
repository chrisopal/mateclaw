package vip.mate.semantic.web;

import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.security.SemanticPrincipalResolver;

@RestController
@RequestMapping("/api/v1/semantic")
public class SemanticStatusController {
    private final SemanticProperties properties;
    private final SemanticPrincipalResolver principal;

    public SemanticStatusController(
            SemanticProperties properties, SemanticPrincipalResolver principal) {
        this.properties = properties;
        this.principal = principal;
    }

    /** Authentication only: feature discovery must work before a workspace is selected. */
    @GetMapping("/status")
    public R<OntologyDtos.Status> status() {
        principal.require();
        return R.ok(new OntologyDtos.Status(properties.isEnabled()));
    }
}

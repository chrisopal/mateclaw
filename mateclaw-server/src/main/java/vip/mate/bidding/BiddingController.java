package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestController
@RequestMapping("/api/v1/bidding")
public class BiddingController {
    private final BiddingAccess access;
    private final BiddingProjectService projects;
    private final BiddingCommandService commands;
    private final BiddingSourceService sources;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    private final ObjectMapper json;

    public BiddingController(BiddingAccess access, BiddingProjectService projects, BiddingCommandService commands,
            BiddingSourceService sources, ObjectProvider<BiddingEmployeeBindings> employees, ObjectMapper json) {
        this.access = access;
        this.projects = projects;
        this.commands = commands;
        this.sources = sources;
        this.employees = employees;
        this.json = json;
    }

    @GetMapping("/capabilities")
    public R<?> capabilities(@RequestHeader("X-Workspace-Id") String workspace) {
        String actor=access.require(workspace,"viewer"); boolean canWrite=hasRole(workspace,"member");
        boolean canApprove=hasRole(workspace,"admin");
        return R.ok(java.util.Map.of("enabled",true,"canWrite",canWrite,"canApprove",canApprove));
    }
    @GetMapping("/employees")
    public R<?> employees(@RequestHeader(value="X-Workspace-Id",required=false) String workspace) {
        String actor=access.require(workspace,"viewer");
        return R.ok(employees.getObject().employees(new BiddingTypes.Scope(workspace,actor,null)));
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
    @PostMapping(value="/projects/{id}/sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<?> uploadSource(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id,
        @RequestParam String operationId,@RequestParam String sourceKind,@RequestParam("file") MultipartFile file,
        @RequestParam(required=false) String supersedesRef) throws Exception {
        String actor=access.require(workspace,"member");
        if(file.getSize()>25L*1024*1024) throw new BiddingApiException(413,"SOURCE_FILE_LIMIT","文件超过25MiB上限");
        String filename=file.getOriginalFilename(), mime=file.getContentType();
        if(mime!=null && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equalsIgnoreCase(mime)) {
            boolean pdf=filename!=null && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") && MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(mime);
            boolean docx=filename!=null && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")
                && "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime);
            if(!pdf && !docx) throw new BiddingApiException(422,"SOURCE_TYPE_MISMATCH","File media type does not match its extension");
        }
        BiddingTypes.Ref supersedes=null;
        if(supersedesRef!=null && !supersedesRef.isBlank()) supersedes=json.readValue(supersedesRef,BiddingTypes.Ref.class);
        return R.ok(sources.upload(new BiddingTypes.Scope(workspace,actor,id),operationId,sourceKind,supersedes,file.getBytes(),filename));
    }
    @GetMapping("/projects/{id}/sources")
    public R<?> listSources(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id) {
        String actor=access.require(workspace,"viewer"); return R.ok(sources.list(new BiddingTypes.Scope(workspace,actor,id)));
    }
    @GetMapping("/projects/{id}/source-set/head")
    public R<?> sourceSetHead(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id) {
        String actor=access.require(workspace,"viewer"); return R.ok(sources.sourceSetHead(new BiddingTypes.Scope(workspace,actor,id)));
    }
    @GetMapping("/projects/{id}/sources/{sourceId}/versions/{version}/content")
    public ResponseEntity<byte[]> sourceContent(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id,
        @PathVariable String sourceId,@PathVariable long version) {
        String actor=access.require(workspace,"viewer"); var content=sources.readContent(new BiddingTypes.Scope(workspace,actor,id),sourceId,version);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(content.filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(content.bytes());
    }
    @GetMapping("/projects/{id}/evidence")
    public R<?> evidence(@RequestHeader(value="X-Workspace-Id",required=false) String workspace,@PathVariable String id,
        @RequestParam String sourceId,@RequestParam long version,@RequestParam String blockId) {
        String actor=access.require(workspace,"viewer"); return R.ok(sources.evidence(new BiddingTypes.Scope(workspace,actor,id),sourceId,version,blockId));
    }
    private boolean hasRole(String workspace,String role) { try { access.require(workspace,role); return true; } catch(BiddingApiException e) { if(e.status()==403) return false; throw e; } }
}

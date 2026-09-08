package vip.mate.semantic.extraction;

import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.application.extraction.ExtractionPorts.SourceContentPort;
import vip.mate.semantic.application.extraction.ExtractionCoordinator;
import vip.mate.semantic.application.extraction.SourceChunker;

public class MateClawSourceAdapter implements SourceContentPort {
    private final JdbcTemplate jdbc;private final GraphApplicationService graphs;private final MateClawAccessAdapter access;private final TransactionTemplate tx;
    public MateClawSourceAdapter(JdbcTemplate jdbc,GraphApplicationService graphs,MateClawAccessAdapter access,PlatformTransactionManager manager){this.jdbc=jdbc;this.graphs=graphs;this.access=access;tx=new TransactionTemplate(manager);}
    public <T>T withGraphLock(Actor actor,String graphId,java.util.function.Supplier<T> action){return tx.execute(s->{access.require(actor,graphId,null,Action.START);graphs.requireGraph(actor.workspaceId(),graphId,true);return action.get();});}
    public SourceSnapshotInput read(Actor actor,String graphId,String sourceRef){return tx.execute(s->{
        access.require(actor,graphId,sourceRef,Action.START);var graph=graphs.requireGraph(actor.workspaceId(),graphId,true);
        var raw=jdbc.queryForMap("SELECT title,COALESCE(NULLIF(extracted_text,''),original_content) text_content FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",sourceRef,graph.getKbId());
        String text=(String)raw.get("text_content");if(text==null)throw new SemanticApiException(422,"SOURCE_EMPTY","Parsed source text required");
        if(text.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new SemanticApiException(422,"SOURCE_TOO_LARGE","Source exceeds snapshot limit");try{new SourceChunker().split(text);}catch(IllegalArgumentException e){throw new SemanticApiException(422,"SOURCE_TOO_LARGE","Source exceeds Unicode or chunk limit");}
        String digest=ExtractionCoordinator.hash(text),title=Objects.toString(raw.get("title"),"");
        var existing=jdbc.query("SELECT id FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind='WIKI_RAW' AND source_id=? AND text_digest=? AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=mate_semantic_source_snapshot.graph_id AND x.snapshot_id=mate_semantic_source_snapshot.id) ORDER BY capture_version DESC",(rs,n)->rs.getString(1),graphId,sourceRef,digest);
        String id=existing.isEmpty()?JdbcExtractionRepository.id():existing.getFirst();
        if(existing.isEmpty()){
            Long version=jdbc.queryForObject("SELECT COALESCE(MAX(capture_version),0)+1 FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind='WIKI_RAW' AND source_id=?",Long.class,graphId,sourceRef);
            jdbc.update("INSERT INTO mate_semantic_source_snapshot(id,graph_id,source_kind,source_id,source_title,capture_version,text_digest,text_content,created_by,created_at) VALUES(?,?,'WIKI_RAW',?,?,?,?,?,?,?)",id,graphId,sourceRef,title,version,digest,text,actor.userId(),JdbcExtractionRepository.at(Instant.now()));
            jdbc.update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1,updated_at=? WHERE id=?",JdbcExtractionRepository.at(Instant.now()),graphId);
        }
        return new SourceSnapshotInput(id,sourceRef,digest,text,Map.of("title",title,"kind","WIKI_RAW"));
    });}
}

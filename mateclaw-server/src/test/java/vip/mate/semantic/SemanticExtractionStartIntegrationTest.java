package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import java.util.*;
import vip.mate.semantic.support.SemanticExtractionFixture;
import static org.junit.jupiter.api.Assertions.*;

class SemanticExtractionStartIntegrationTest extends SemanticExtractionFixture {
    @Test void startReplayScopeRevocationAndUnknownFields()throws Exception{
        var command=new HashMap<String,Object>(Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()));
        var first=call("POST",base+"/extraction-tasks","member",workspace,command,202);
        assertTrue(first.path("version").isNumber());assertTrue(first.path("taskId").isTextual());
        assertEquals(first.path("taskId"),call("POST",base+"/extraction-tasks","member",workspace,command,202).path("taskId"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE graph_id=?",Integer.class,graph));
        call("GET",base+"/extraction-tasks/"+first.path("taskId").asText(),"owner",otherWorkspace,null,404);
        command.put("userId","spoof");call("POST",base+"/extraction-tasks","member",workspace,command,400);command.remove("userId");
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",raw);
        call("GET",base+"/extraction-tasks/"+first.path("taskId").asText(),"member",workspace,null,404);
        assertEquals(0,call("GET",base+"/extraction-tasks","viewer",workspace,null,200).path("total").asInt());
        call("POST",base+"/extraction-tasks","member",workspace,command,404);
    }
    @Test void changedPayloadConflictAndViewerCannotStart()throws Exception{
        var command=new HashMap<String,Object>(Map.of("sourceRef",raw,"modelConfigId","8","operationId",op()));
        call("POST",base+"/extraction-tasks","viewer",workspace,command,403);
        call("POST",base+"/extraction-tasks","member",workspace,command,202);
        jdbc.update("UPDATE mate_wiki_raw_material SET extracted_text=? WHERE id=?",text+"new",raw);
        call("POST",base+"/extraction-tasks","member",workspace,command,409);
    }
}

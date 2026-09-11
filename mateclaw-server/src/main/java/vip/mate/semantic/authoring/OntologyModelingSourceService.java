package vip.mate.semantic.authoring;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.semantic.core.ontology.OntologyDocument;

/** Source discovery never silently truncates a collection or source body. */
@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class OntologyModelingSourceService {
    private final JdbcTemplate jdbc;
    private final WikiKnowledgeBaseService knowledgeBases;
    private final AgentMapper agents;
    private final SemanticAccessService access;
    public OntologyModelingSourceService(JdbcTemplate jdbc,WikiKnowledgeBaseService knowledgeBases,AgentMapper agents,SemanticAccessService access) {
        this.jdbc=jdbc;this.knowledgeBases=knowledgeBases;this.agents=agents;this.access=access;
    }
    public void visible(String scope,Long agentId,String kbId) {
        access.require(scope,"viewer");
        var agent=agentId==null?null:agents.selectById(agentId);
        if(agent==null||!Boolean.TRUE.equals(agent.getEnabled())||!Objects.equals(agent.getWorkspaceId(),Long.valueOf(scope)))throw unavailable();
        if(kbId!=null) {
            var kb=knowledgeBases.findVisibleById(agentId,positive(kbId));
            if(kb==null||!Objects.equals(kb.getWorkspaceId(),Long.valueOf(scope)))throw unavailable();
        }
    }
    public Map<String,Object> page(String scope,Long agentId,String kbId,int page,int pageSize) {
        visible(scope,agentId,kbId==null||kbId.isBlank()?null:kbId);
        if(page<1||page>100000||pageSize<1||pageSize>50)throw new SemanticApiException(400,"INVALID_PAGE","Page >= 1 and pageSize 1..50 required");
        int offset=(page-1)*pageSize;
        List<?> items;long total;
        if(kbId==null||kbId.isBlank()) {
            var all=knowledgeBases.listByAgentId(agentId).stream().filter(k->Objects.equals(k.getWorkspaceId(),Long.valueOf(scope))).sorted(Comparator.comparing(k->k.getId())).toList();
            total=all.size();items=all.stream().skip(offset).limit(pageSize).map(k->Map.of("knowledgeBaseId",k.getId().toString(),"name",Objects.toString(k.getName(),""))).toList();
        } else {
            total=jdbc.queryForObject("SELECT COUNT(*) FROM mate_wiki_raw_material WHERE kb_id=? AND deleted=0",Long.class,positive(kbId));
            items=jdbc.query("SELECT id,title,processing_status,COALESCE(NULLIF(extracted_text,''),original_content) AS content FROM mate_wiki_raw_material WHERE kb_id=? AND deleted=0 ORDER BY id LIMIT ? OFFSET ?",(rs,n)->metadata(kbId,rs.getString("id"),rs.getString("title"),rs.getString("processing_status"),rs.getString("content")),positive(kbId),pageSize,offset);
        }
        return Map.of("items",items,"total",Long.toString(total),"page",page,"pageSize",pageSize,"hasMore",offset+items.size()<total);
    }
    private Map<String,Object> metadata(String kb,String source,String title,String status,String body) {
        String text=Objects.toString(body,"");String reason=text.isBlank()?"NOT_PARSED":text.length()>1_000_000?"SOURCE_TOO_LARGE":"";
        return Map.of("knowledgeBaseId",kb,"sourceRef",source,"title",Objects.toString(title,""),"processingStatus",Objects.toString(status,""),"sourceDigest",OntologyDocument.sha256(text),"totalCodePoints",text.codePointCount(0,text.length()),"readable",reason.isEmpty(),"unreadReason",reason);
    }
    public Map<String,Object> chunk(String scope,Long agentId,String kb,String source,String digest,int start,int length) {
        visible(scope,agentId,kb);
        var rows=jdbc.query("SELECT title,processing_status,COALESCE(NULLIF(extracted_text,''),original_content) AS content FROM mate_wiki_raw_material WHERE kb_id=? AND id=? AND deleted=0",(rs,n)->Map.entry(metadata(kb,source,rs.getString("title"),rs.getString("processing_status"),rs.getString("content")),Objects.toString(rs.getString("content"),"")),positive(kb),positive(source));
        if(rows.isEmpty())throw unavailable();
        var row=rows.getFirst();var result=new LinkedHashMap<String,Object>(row.getKey());
        if(digest!=null&&!digest.equals(result.get("sourceDigest")))throw new SemanticApiException(409,"SOURCE_CHANGED","Selected source version changed");
        int total=(Integer)result.get("totalCodePoints");
        if(start<0||start>total||length<1||length>16000)throw new SemanticApiException(400,"INVALID_RANGE","Code point start and length 1..16000 required");
        int end=Boolean.TRUE.equals(result.get("readable"))?Math.min(total,start+length):start;
        result.put("content",row.getValue().substring(row.getValue().offsetByCodePoints(0,start),row.getValue().offsetByCodePoints(0,end)));
        result.put("startCodePoint",start);result.put("endCodePoint",end);result.put("hasMore",end<total);result.put("remainingCodePoints",total-end);
        return result;
    }
    private static long positive(String value){try{long id=Long.parseLong(value);if(id<=0)throw unavailable();return id;}catch(RuntimeException e){throw unavailable();}}
    private static SemanticApiException unavailable(){return new SemanticApiException(404,"SOURCE_UNAVAILABLE","Source or agent is unavailable or not visible");}
}

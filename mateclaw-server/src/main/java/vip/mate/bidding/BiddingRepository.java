package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BiddingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    public BiddingRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public ObjectNode findProject(String workspaceId,String projectId) {
        List<ObjectNode> rows=jdbc.query("SELECT body_json FROM mate_bidding_project WHERE workspace_id=:workspaceId AND id=:id",
            Map.of("workspaceId",workspaceId,"id",projectId),(rs,n)->readObject(rs,"body_json"));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int insertProject(String id,String workspaceId,String ownerId,String name,String lotName,String body,long version,String digest,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_project(id,workspace_id,project_id,owner_id,version,name,lot_name,stage,body_json,created_at,updated_at) VALUES(:id,:workspaceId,:id,:ownerId,:version,:name,:lotName,'SETUP',:body,:now,:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("workspaceId",workspaceId).addValue("ownerId",ownerId)
                .addValue("version",version).addValue("name",name).addValue("lotName",lotName).addValue("body",body).addValue("now",now));
    }
    public boolean lockProject(String workspace,String project) {
        List<String> rows=jdbc.query("SELECT id FROM mate_bidding_project WHERE workspace_id=:w AND id=:p FOR UPDATE",Map.of("w",workspace,"p",project),(rs,n)->rs.getString(1));
        return !rows.isEmpty();
    }
    public void insertOperation(String workspaceId,String actorId,String operationId,String digest,String result,java.sql.Timestamp now) {
        jdbc.update("INSERT INTO mate_bidding_operation(workspace_id,actor_id,operation_id,request_digest,result_json,created_at) VALUES(:workspace,:actor,:operation,:digest,:result,:now)",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId,"digest",digest,"result",result,"now",now));
    }
    public void updateOperation(String workspaceId,String actorId,String operationId,String result) {
        jdbc.update("UPDATE mate_bidding_operation SET result_json=:result WHERE workspace_id=:workspace AND actor_id=:actor AND operation_id=:operation",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId,"result",result));
    }
    public StoredOperation findOperation(String workspaceId,String actorId,String operationId) {
        List<StoredOperation> rows=jdbc.query("SELECT request_digest,result_json FROM mate_bidding_operation WHERE workspace_id=:workspace AND actor_id=:actor AND operation_id=:operation",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId),(rs,n)->new StoredOperation(rs.getString(1),parseObject(rs.getString(2))));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public BiddingTypes.Page<ObjectNode> list(String workspaceId,String query,String stage,String ownerId,int page,int pageSize) {
        String q= query==null?"":query.trim();
        var params=new MapSqlParameterSource().addValue("workspace",workspaceId).addValue("q","%"+q+"%")
            .addValue("stage",stage).addValue("owner",ownerId).addValue("limit",pageSize).addValue("offset",(long)(page-1)*pageSize);
        String where=" WHERE workspace_id=:workspace AND (:q='%%' OR LOWER(name) LIKE LOWER(:q) OR LOWER(lot_name) LIKE LOWER(:q)) AND (:stage IS NULL OR stage=:stage) AND (:owner IS NULL OR owner_id=:owner)";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_project"+where,params,Long.class);
        List<ObjectNode> items=jdbc.query("SELECT body_json FROM mate_bidding_project"+where+" ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset",params,(rs,n)->readObject(rs,"body_json"));
        return new BiddingTypes.Page<>(items,total,page,pageSize);
    }
    public BiddingTypes.Ref sourceSetHead(BiddingTypes.Scope scope) {
        try { return json.convertValue(parseObject(jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class)),BiddingTypes.Ref.class); }
        catch(org.springframework.dao.EmptyResultDataAccessException e) { return null; }
    }
    public boolean advanceSourceSetHead(BiddingTypes.Scope scope,BiddingTypes.Ref expected,BiddingTypes.Ref next,String refJson) {
        var args=new MapSqlParameterSource().addValue("w",scope.workspaceId()).addValue("p",scope.projectId()).addValue("v",next.version()).addValue("ref",refJson);
        if(expected==null) {
            try { jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(:w,:p,'sourceSet','current',:v,:ref)",args); return true; }
            catch(org.springframework.dao.DuplicateKeyException conflict) { return false; }
        }
        args.addValue("expectedVersion",expected.version());
        return jdbc.update("UPDATE mate_bidding_head SET version=:v,selected_ref_json=:ref WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version=:expectedVersion",args)==1;
    }
    public boolean sourceWasConfirmed(BiddingTypes.Scope scope,BiddingTypes.Ref sourceRef) {
        List<String> histories=jdbc.query("SELECT input_refs_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet'",
            Map.of("w",scope.workspaceId(),"p",scope.projectId()),(rs,n)->rs.getString(1));
        for(String history:histories) {
            try {
                JsonNode refs=json.readTree(history);
                if(refs.isArray()) for(JsonNode node:refs) if(sourceRef.equals(json.convertValue(node,BiddingTypes.Ref.class))) return true;
            } catch(Exception e) { throw new IllegalStateException("Invalid persisted source-set references",e); }
        }
        return false;
    }
    public int insertSource(String id,String workspace,String project,String sourceId,long version,String kind,String digest,byte[] content,
        String filename,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) " +
            "VALUES(:id,:w,:p,:source,:version,:kind,:digest,:content,'[]','PENDING',NULL,NULL,:filename,'PENDING','[]',:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("w",workspace).addValue("p",project).addValue("source",sourceId).addValue("version",version)
                .addValue("kind",kind).addValue("digest",digest).addValue("content",content).addValue("filename",filename).addValue("now",now));
    }
    public SourceRow source(String workspace,String project,String sourceId,long version) {
        var rows=jdbc.query("SELECT id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p AND source_id=:s AND version=:v",
            Map.of("w",workspace,"p",project,"s",sourceId,"v",version),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),rs.getBytes("content"),rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<SourceRow> pendingSources(int limit) {
        return jdbc.query("SELECT id,workspace_id,project_id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE read_status='PENDING' ORDER BY created_at LIMIT :limit",
            Map.of("limit",Math.min(2,Math.max(0,limit))),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("workspace_id"),rs.getString("project_id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),rs.getBytes("content"),rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
    }
    public int claimSource(String rowId,String token,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='READING',read_token=:token,read_started_at=:now WHERE id=:id AND read_status='PENDING'",Map.of("id",rowId,"token",token,"now",now));
    }
    public int finishSource(String rowId,String token,String status,String blocks,String quality,String problems,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status=:status,blocks_json=:blocks,quality=:quality,problems_json=:problems,read_completed_at=:now WHERE id=:id AND read_status='READING' AND read_token=:token",
            Map.of("id",rowId,"token",token,"status",status,"blocks",blocks,"quality",quality,"problems",problems,"now",now));
    }
    public int recoverReadingSources(java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='FAILED',quality='FAILED',problems_json='[\"READ_INTERRUPTED\"]',read_completed_at=:now WHERE read_status='READING'",Map.of("now",now));
    }
    public int invalidateSourceSet(BiddingTypes.Scope scope) {
        int derived=jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND status='CONFIRMED' AND kind<>'sourceSet' AND input_refs_json LIKE :needle",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"needle","%sourceSet%current%"));
        jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version<(SELECT MAX(v.version) FROM mate_bidding_revision v WHERE v.workspace_id=:w AND v.project_id=:p AND v.kind='sourceSet' AND v.object_id='current')",
            Map.of("w",scope.workspaceId(),"p",scope.projectId()));
        return derived;
    }
    public int retrySource(String workspace,String project,String sourceId,long version) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='PENDING',quality='PENDING',blocks_json='[]',problems_json='[]',read_token=NULL,read_started_at=NULL,read_completed_at=NULL WHERE workspace_id=:w AND project_id=:p AND source_id=:s AND version=:v AND read_status IN ('FAILED','NEEDS_REVIEW')",
            Map.of("w",workspace,"p",project,"s",sourceId,"v",version));
    }
    public int insertRevision(String id,String workspace,String project,String kind,String objectId,long version,String payload,String refs,String status,String digest,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(:id,:w,:p,:kind,:object,:version,:payload,:refs,:status,:digest,:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("w",workspace).addValue("p",project).addValue("kind",kind).addValue("object",objectId)
                .addValue("version",version).addValue("payload",payload).addValue("refs",refs).addValue("status",status).addValue("digest",digest).addValue("now",now));
    }
    public boolean revisionExists(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object AND version=:version AND digest=:digest AND status='CONFIRMED'",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id(),"version",ref.version(),"digest",ref.digest()),Integer.class);
        return count!=null && count==1;
    }
    public boolean dependenciesCurrent(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        try { var head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            return refs.stream().anyMatch(ref -> ref.kind().equals("sourceSet") && ref.digest().equals(parseObject(head).path("digest").asText()));
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public boolean isSelectedSourceSet(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        try {
            String head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            ObjectNode selected=parseObject(head);
            return "sourceSet".equals(ref.kind()) && "current".equals(ref.id()) && selected.path("version").asLong()==ref.version() && selected.path("digest").asText().equals(ref.digest());
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public boolean sourceSetContains(BiddingTypes.Scope scope,BiddingTypes.Ref sourceRef) {
        try {
            String head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            ObjectNode selected=parseObject(head);
            String payload=jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version=:v AND status='CONFIRMED'",
                Map.of("w",scope.workspaceId(),"p",scope.projectId(),"v",selected.path("version").asLong()),String.class);
            JsonNode refs=parseObject(payload).path("sourceRefs");
            for(JsonNode ref:refs) if(ref.path("kind").asText().equals(sourceRef.kind()) && ref.path("id").asText().equals(sourceRef.id())
                && ref.path("version").asLong()==sourceRef.version() && ref.path("digest").asText().equals(sourceRef.digest())) return true;
            return false;
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public void invalidateDependencies(BiddingTypes.Scope scope,BiddingTypes.Ref changed) {
        jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND status='CONFIRMED' AND input_refs_json LIKE :needle",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"needle","%"+changed.id()+"%"));
    }
    public List<SourceRow> sources(String workspace,String project) {
        return jdbc.query("SELECT id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p ORDER BY created_at,source_id,version",
            Map.of("w",workspace,"p",project),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),null,rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
    }
    public int insertSourceHead(BiddingTypes.Scope scope,String sourceId,long version,String refJson) {
        jdbc.update("DELETE FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='source' AND object_id=:id",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId));
        return jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(:w,:p,'source',:id,:v,:ref)",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId,"v",version,"ref",refJson));
    }
    public long maxSourceVersion(BiddingTypes.Scope scope,String sourceId) {
        Long result=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p AND source_id=:id",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId),Long.class);
        return result==null?0:result;
    }
    public long maxRevisionVersion(BiddingTypes.Scope scope,String kind,String objectId) {
        Long result=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",kind,"object",objectId),Long.class);
        return result==null?0:result;
    }
    public record SourceRow(String rowId,String workspaceId,String projectId,String sourceId,long version,String kind,String digest,byte[] content,String filename,String blocks,String quality,String status,String problems,String readToken) {
        public SourceRow(String rowId,String sourceId,long version,String kind,String digest,byte[] content,String filename,String blocks,String quality,String status,String problems,String readToken) {
            this(rowId,null,null,sourceId,version,kind,digest,content,filename,blocks,quality,status,problems,readToken);
        }
    }
    public int updateProject(String workspace,String projectId,String ownerId,String name,String lotName,String stage,String body,int expectedVersion,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_project SET owner_id=:owner,name=:name,lot_name=:lotName,stage=:stage,body_json=:body,version=version+1,updated_at=:now WHERE id=:id AND workspace_id=:workspace AND version=:version",
            Map.of("owner",ownerId,"name",name,"lotName",lotName,"stage",stage,"body",body,"now",now,"id",projectId,"workspace",workspace,"version",expectedVersion));
    }
    public record StoredOperation(String digest,ObjectNode result) {}
    private ObjectNode readObject(ResultSet rs,String column) { try { return (ObjectNode)json.readTree(rs.getString(column)); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
    private ObjectNode parseObject(String value) { try { return (ObjectNode)json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
}

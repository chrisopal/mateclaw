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
    public int updateProject(String workspace,String projectId,String ownerId,String name,String lotName,String stage,String body,int expectedVersion,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_project SET owner_id=:owner,name=:name,lot_name=:lotName,stage=:stage,body_json=:body,version=version+1,updated_at=:now WHERE id=:id AND workspace_id=:workspace AND version=:version",
            Map.of("owner",ownerId,"name",name,"lotName",lotName,"stage",stage,"body",body,"now",now,"id",projectId,"workspace",workspace,"version",expectedVersion));
    }
    public record StoredOperation(String digest,ObjectNode result) {}
    private ObjectNode readObject(ResultSet rs,String column) { try { return (ObjectNode)json.readTree(rs.getString(column)); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
    private ObjectNode parseObject(String value) { try { return (ObjectNode)json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
}

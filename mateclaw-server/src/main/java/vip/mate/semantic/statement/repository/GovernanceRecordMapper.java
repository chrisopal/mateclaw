package vip.mate.semantic.statement.repository;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface GovernanceRecordMapper {
    @Insert(
            "INSERT INTO"
                + " mate_semantic_governance_record(id,workspace_id,ontology_id,revision_id,action,actor_id,detail_json,created_at)"
                + " VALUES(#{id},#{workspace},#{ontology},#{revision},#{action},#{actor},#{detail},#{created})")
    int insert(
            @Param("id") String id,
            @Param("workspace") long workspace,
            @Param("ontology") String ontology,
            @Param("revision") String revision,
            @Param("action") String action,
            @Param("actor") String actor,
            @Param("detail") String detail,
            @Param("created") LocalDateTime created);
}

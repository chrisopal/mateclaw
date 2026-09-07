package vip.mate.semantic.statement.repository;

import org.apache.ibatis.annotations.*;

import vip.mate.semantic.statement.CommandRecordRow;

@Mapper
public interface CommandRecordMapper {
    @Select(
            "SELECT * FROM mate_semantic_command_record WHERE workspace_id=#{workspace} AND"
                    + " operation_id=#{operation}")
    CommandRecordRow find(@Param("workspace") long workspace, @Param("operation") String operation);

    @Insert(
            "INSERT INTO"
                + " mate_semantic_command_record(id,workspace_id,operation_id,kind,resource_id,payload_hash,result_json,created_at)"
                + " VALUES(#{id},#{workspaceId},#{operationId},#{kind},#{resourceId},#{payloadHash},#{resultJson},#{createdAt})")
    int insert(CommandRecordRow row);
}

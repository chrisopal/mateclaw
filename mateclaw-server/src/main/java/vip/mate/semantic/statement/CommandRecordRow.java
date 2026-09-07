package vip.mate.semantic.statement;

import lombok.Data;

@Data
public class CommandRecordRow {
    private String id;
    private Long workspaceId;
    private String operationId;
    private String kind;
    private String resourceId;
    private String payloadHash;
    private String resultJson;
    private java.time.LocalDateTime createdAt;
}

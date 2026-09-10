package vip.mate.semantic.graph.repository;

import org.apache.ibatis.annotations.*;
import vip.mate.semantic.graph.EntityRow;
import vip.mate.semantic.graph.GraphOntologyRevisionRow;
import vip.mate.semantic.graph.GraphRow;

import java.util.List;

@Mapper
public interface GraphMapper {
    @Select("SELECT * FROM mate_semantic_graph WHERE workspace_id=#{workspace} AND kb_id=#{kb}")
    GraphRow byKnowledgeBase(@Param("workspace") long workspace, @Param("kb") long kb);

    @Select("SELECT * FROM mate_semantic_graph WHERE id=#{id} AND workspace_id=#{workspace}")
    GraphRow find(@Param("id") String id, @Param("workspace") long workspace);

    @Select("SELECT * FROM mate_semantic_graph WHERE id=#{id} AND workspace_id=#{workspace} FOR UPDATE")
    GraphRow lock(@Param("id") String id, @Param("workspace") long workspace);

    @Select("SELECT workspace_id FROM mate_wiki_knowledge_base WHERE id=#{kb} AND deleted=0")
    Long knowledgeBaseWorkspace(long kb);

    @Select("SELECT r.*,o.workspace_id FROM mate_semantic_ontology_revision r JOIN mate_semantic_ontology o ON o.id=r.ontology_id WHERE r.id=#{id}")
    GraphOntologyRevisionRow revision(String id);

    @Insert("INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,mutation_version,created_at,updated_at) VALUES(#{id},#{workspaceId},#{kbId},#{ontologyRevisionId},#{enabled},#{mutationVersion},#{createdAt},#{updatedAt})")
    int insert(GraphRow row);

    @Update("UPDATE mate_semantic_graph SET ontology_revision_id=#{row.ontologyRevisionId},enabled=#{row.enabled},mutation_version=mutation_version+1,updated_at=#{row.updatedAt} WHERE id=#{row.id} AND mutation_version=#{expected}")
    int update(@Param("row") GraphRow row, @Param("expected") long expected);

    @Select("SELECT COUNT(*) FROM mate_semantic_entity WHERE graph_id=#{graph}")
    long entityCount(String graph);

    @Select("SELECT (SELECT COUNT(*) FROM mate_semantic_entity WHERE graph_id=#{graph}) + (SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE graph_id=#{graph}) + (SELECT COUNT(*) FROM mate_semantic_import_job WHERE graph_id=#{graph}) + (SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=#{graph})")
    long contentCount(String graph);

    @Select("SELECT * FROM mate_semantic_entity WHERE graph_id=#{graph} ORDER BY created_at,id")
    List<EntityRow> entities(String graph);

    @Insert("INSERT INTO mate_semantic_entity(id,graph_id,iri,iri_digest,asserted_types_json,display_name,status,created_by,created_at) VALUES(#{id},#{graphId},#{iri},#{iriDigest},#{assertedTypesJson},#{displayName},#{status},#{createdBy},#{createdAt})")
    int insertEntity(EntityRow row);

    @Update("UPDATE mate_semantic_graph SET mutation_version=mutation_version+1,updated_at=#{updatedAt} WHERE id=#{id} AND mutation_version=#{expected}")
    int touch(@Param("id") String id, @Param("expected") long expected, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Select("SELECT g.* FROM mate_semantic_graph g JOIN mate_semantic_ontology_revision r ON r.id=g.ontology_revision_id WHERE r.ontology_id=#{ontology} AND g.workspace_id=#{workspace} ORDER BY g.updated_at DESC")
    List<GraphRow> bindings(@Param("ontology") String ontology, @Param("workspace") long workspace);
}

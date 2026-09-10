package vip.mate.semantic.ontology.repository;

import org.apache.ibatis.annotations.*;

import vip.mate.semantic.ontology.*;

import java.util.List;

@Mapper
public interface OntologyMapper {
    @Select("SELECT * FROM mate_semantic_ontology WHERE id=#{id} AND workspace_id=#{workspace}")
    OntologyRow find(@Param("id") String id, @Param("workspace") long workspace);

    @Select(
            "SELECT * FROM mate_semantic_ontology WHERE id=#{id} AND workspace_id=#{workspace} FOR"
                    + " UPDATE")
    OntologyRow lock(@Param("id") String id, @Param("workspace") long workspace);

    @Select(
            "SELECT * FROM mate_semantic_ontology WHERE workspace_id=#{workspace} AND LOWER(name)"
                    + " LIKE #{query} ORDER BY updated_at DESC,id LIMIT #{limit} OFFSET #{offset}")
    List<OntologyRow> list(
            @Param("workspace") long workspace,
            @Param("query") String query,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(
            "SELECT COUNT(*) FROM mate_semantic_ontology WHERE workspace_id=#{workspace} AND"
                    + " LOWER(name) LIKE #{query}")
    long count(@Param("workspace") long workspace, @Param("query") String query);

    @Insert(
            "INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,updated_at)"
                    + " VALUES(#{id},#{workspaceId},#{name},#{description},#{updatedAt})")
    int insert(OntologyRow row);

    @Update(
            "UPDATE mate_semantic_ontology SET"
                + " name=#{name},description=#{description},draft_id=#{draftId},draft_counter=#{draftCounter},latest_version=#{latestVersion},latest_revision_id=#{latestRevisionId},updated_at=#{updatedAt}"
                + " WHERE id=#{id}")
    int updateParent(OntologyRow row);

    @Select(
            "SELECT * FROM mate_semantic_ontology_revision WHERE id=#{id} AND"
                    + " ontology_id=#{ontology}")
    OntologyRevisionRow revision(@Param("id") String id, @Param("ontology") String ontology);

    @Select(
            "SELECT * FROM mate_semantic_ontology_revision WHERE ontology_id=#{ontology} AND"
                    + " revision_state='PUBLISHED' ORDER BY version DESC")
    List<OntologyRevisionRow> revisions(String ontology);

    @Insert(
            "INSERT INTO"
                + " mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,document_text,document_syntax,document_digest,ontology_iri,version_iri,import_lock_digest,model_schema,imports_json,policy_json,base_revision_id,available_for_new_bindings)"
                + " VALUES(#{id},#{ontologyId},#{version},#{draftVersion},'DRAFT',1,#{name},#{description},#{documentText},#{documentSyntax},#{documentDigest},#{ontologyIri},#{versionIri},#{importLockDigest},#{modelSchema},#{importsJson},#{policyJson},#{baseRevisionId},FALSE)")
    int insertDraft(OntologyRevisionRow row);

    @Update(
            "UPDATE mate_semantic_ontology_revision SET"
                + " name=#{row.name},description=#{row.description},document_text=#{row.documentText},document_syntax=#{row.documentSyntax},document_digest=#{row.documentDigest},ontology_iri=#{row.ontologyIri},version_iri=#{row.versionIri},import_lock_digest=#{row.importLockDigest},model_schema=#{row.modelSchema},imports_json=#{row.importsJson},policy_json=#{row.policyJson},draft_version=draft_version+1"
                + " WHERE id=#{row.id} AND revision_state='DRAFT' AND draft_version=#{expected}")
    int saveDraft(@Param("row") OntologyRevisionRow row, @Param("expected") long expected);

    @Delete(
            "DELETE FROM mate_semantic_ontology_revision WHERE id=#{id} AND revision_state='DRAFT'"
                    + " AND draft_version=#{expected}")
    int deleteDraft(@Param("id") String id, @Param("expected") long expected);

    @Update(
            "UPDATE mate_semantic_ontology_revision SET"
                + " revision_state='PUBLISHED',draft_slot=NULL,available_for_new_bindings=TRUE,published_at=#{row.publishedAt},published_by=#{row.publishedBy},publication_note=#{row.publicationNote}"
                + " WHERE id=#{row.id} AND revision_state='DRAFT' AND draft_version=#{expected}")
    int publish(@Param("row") OntologyRevisionRow row, @Param("expected") long expected);

    @Update(
            "UPDATE mate_semantic_ontology_revision SET available_for_new_bindings=#{available}"
                    + " WHERE id=#{id} AND revision_state='PUBLISHED'")
    int availability(@Param("id") String id, @Param("available") boolean available);
}

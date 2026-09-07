package vip.mate.semantic.statement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import vip.mate.semantic.graph.GraphRow;

import java.util.*;

@Service
public class SupportEvaluator {
    private final JdbcTemplate jdbc;
    public SupportEvaluator(JdbcTemplate jdbc){this.jdbc=jdbc;}

    /** Evaluates all current revisions with two queries, independent of fact count. */
    public Set<String> supportedCurrentStatements(GraphRow graph){
        Set<String> liveSources=new HashSet<>(jdbc.query("SELECT id FROM mate_wiki_raw_material WHERE kb_id=? AND deleted=0",(rs,n)->rs.getString(1),graph.getKbId()));
        Set<String> supported=new HashSet<>();
        jdbc.query("SELECT DISTINCT st.id,s.source_id FROM mate_semantic_statement st JOIN mate_semantic_revision_evidence re ON re.statement_id=st.id AND re.revision=st.current_revision JOIN mate_semantic_evidence e ON e.id=re.evidence_id AND e.graph_id=st.graph_id JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE st.graph_id=? AND s.source_kind='WIKI_RAW' AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=st.graph_id AND x.snapshot_id=s.id) AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance g WHERE g.graph_id=st.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id AND g.state='WITHDRAWN')",rs->{
            if(liveSources.contains(rs.getString("source_id")))supported.add(rs.getString("id"));
        },graph.getId());
        return Set.copyOf(supported);
    }

    public boolean supported(GraphRow graph,String statementId,int revision){
        List<String> evidenceIds=jdbc.query(
                "SELECT evidence_id FROM mate_semantic_revision_evidence WHERE statement_id=? AND revision=?",
                (rs,n)->rs.getString(1),statementId,revision);
        return supported(graph,evidenceIds);
    }

    /** Returns true when at least one referenced evidence item still has an active source. */
    public boolean supported(GraphRow graph,Collection<String> evidenceIds){
        if(evidenceIds==null||evidenceIds.isEmpty())return false;
        String placeholders=String.join(",",Collections.nCopies(evidenceIds.size(),"?"));
        List<Object> args=new ArrayList<>();args.add(graph.getId());args.addAll(evidenceIds);
        List<SupportRow> rows=jdbc.query("SELECT e.id,s.id snapshot_id,s.source_kind,s.source_id FROM mate_semantic_evidence e JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE e.graph_id=? AND e.id IN ("+placeholders+") AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=e.graph_id AND x.snapshot_id=s.id) AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance g WHERE g.graph_id=e.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id AND g.state='WITHDRAWN')",
                (rs,n)->new SupportRow(rs.getString("id"),rs.getString("snapshot_id"),rs.getString("source_kind"),rs.getString("source_id")),args.toArray());
        for(SupportRow row:rows){
            if("WIKI_RAW".equals(row.kind())){
                try{
                    Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",Integer.class,Long.valueOf(row.source()),graph.getKbId());
                    if(count!=null&&count>0)return true;
                }catch(NumberFormatException ignored){}
            }
        }
        return false;
    }
    private record SupportRow(String evidence,String snapshot,String kind,String source){}
}

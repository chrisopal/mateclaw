package vip.mate.semantic.statement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import vip.mate.semantic.graph.GraphRow;

import java.util.*;

@Service
public class SupportEvaluator {
    private final JdbcTemplate jdbc;
    public SupportEvaluator(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public boolean supported(GraphRow graph,String statementId,int revision){
        List<SupportRow> rows=jdbc.query("SELECT e.id,s.id snapshot_id,s.source_kind,s.source_id FROM mate_semantic_revision_evidence re JOIN mate_semantic_evidence e ON e.id=re.evidence_id JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE re.statement_id=? AND re.revision=? AND e.graph_id=? AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=e.graph_id AND x.snapshot_id=s.id) AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance g WHERE g.graph_id=e.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id AND g.state='WITHDRAWN')",
                (rs,n)->new SupportRow(rs.getString("id"),rs.getString("snapshot_id"),rs.getString("source_kind"),rs.getString("source_id")),statementId,revision,graph.getId());
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

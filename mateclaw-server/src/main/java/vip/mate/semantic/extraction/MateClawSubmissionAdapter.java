package vip.mate.semantic.extraction;

import java.util.*;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.source.SourceApplicationService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SourceDtos.EvidenceRequest;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.application.extraction.ExtractionPorts.KnowledgeSubmissionPort;

/** Existing source evidence + PROPOSED authority, with stable operations for every replay. */
public class MateClawSubmissionAdapter implements KnowledgeSubmissionPort {
    private final SourceApplicationService sources;private final StatementApplicationService statements;private final MateClawAccessAdapter access;private final vip.mate.semantic.graph.GraphApplicationService graphs;private final MateClawContextAdapter context;private final org.springframework.transaction.support.TransactionTemplate tx;
    public MateClawSubmissionAdapter(SourceApplicationService sources,StatementApplicationService statements,MateClawAccessAdapter access,vip.mate.semantic.graph.GraphApplicationService graphs,MateClawContextAdapter context,org.springframework.transaction.PlatformTransactionManager manager){this.sources=sources;this.statements=statements;this.access=access;this.graphs=graphs;this.context=context;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);}
    public SubmissionRef submit(Actor actor,String graph,StatementContent content,SourceSnapshotInput source,List<Quote> quotes,String operation){return tx.execute(status->{
        var locked=graphs.requireGraph(actor.workspaceId(),graph,true);
        if(!locked.getOntologyRevisionId().equals(content.ontologyRevisionId().value()))throw new vip.mate.semantic.application.extraction.ExtractionException(409,"BINDING_CHANGED");
        context.requireSnapshot(actor,graph,source);
        access.require(actor,graph,source.sourceRef(),Action.SUBMIT);
        List<String> evidence=new ArrayList<>();int index=0;
        for(Quote quote:quotes)evidence.add(sources.createEvidence(actor.workspaceId(),graph,source.snapshotId(),new EvidenceRequest(operation+"-e"+index++,quote.startCodePoint(),quote.endCodePoint(),quote.exactQuote())).id());
        var validity=content.validity();
        var request=new ProposeRequest(operation,content.subjectId().value(),content.assertion().functionalSyntax(),validity.kind().name(),validity.fromInclusive(),validity.toExclusive(),evidence);
        var result=statements.propose(actor.workspaceId(),graph,request);return new SubmissionRef(result.id(),result.revision());
    });}
}

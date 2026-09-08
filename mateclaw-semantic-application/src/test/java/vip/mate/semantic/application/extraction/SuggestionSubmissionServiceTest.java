package vip.mate.semantic.application.extraction;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.SemanticIds.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static vip.mate.semantic.application.extraction.ExtractionPorts.*;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionSubmissionServiceTest {
    @Test void lostReceiptReusesStableProposeOperationAndRestoresOriginalReference(){
        var fixture=new ExtractionCoordinatorTest();String text="Voltage 220V";
        var raw=new RawSuggestion(new ObjectMention("M","Equipment","Machine"),PredicateRef.property("voltage"),new StatementValue.DecimalValue("220","V"),null,Validity.unknown(),List.of(new Quote(0,text.length(),text)));
        var content=new StatementContent(fixture.scope,fixture.ontology.revisionId(),new EntityId("11"),raw.predicate(),raw.value(),raw.validity(),Set.of());
        var suggestion=new Suggestion("12","13","14",raw,content,List.of(),2,SuggestionStatus.OPEN);
        var task=new Task("13",fixture.actor,"4","start","hash",new SourceSnapshotInput("9","10","digest",text,Map.of()),fixture.ontology,"def","v1",fixture.config,TaskStatus.SUCCEEDED,3,false,fixture.now,fixture.now);
        Receipt[] receipt={null};String[] reserved={null};AtomicBoolean fail=new AtomicBoolean(true);List<String> operations=new ArrayList<>();
        ExtractionTaskRepository repo=ExtractionCoordinatorTest.port(ExtractionTaskRepository.class,(name,args)->switch(name){
            case "suggestion"->Optional.of(suggestion);case "find"->Optional.of(task);case "receipt"->Optional.ofNullable(receipt[0]);
            case "reserveSubmission"->{String operation=(String)args[3];if(reserved[0]!=null&&!reserved[0].equals(operation))throw new ExtractionException(409,"OPERATION_CONFLICT");reserved[0]=operation;yield null;}
            case "saveReceipt"->{if(fail.getAndSet(false))throw new IllegalStateException("injected receipt failure");receipt[0]=(Receipt)args[2];yield null;}
            default->throw new AssertionError(name);
        });
        ContextPort context=ExtractionCoordinatorTest.port(ContextPort.class,(name,args)->switch(name){
            case "ontology"->fixture.ontology;case "requireSnapshot"->null;case "scope"->fixture.scope;
            case "entities"->Map.of(new EntityId("11"),new Entity(new EntityId("11"),fixture.scope,"Equipment","Machine"));default->throw new AssertionError(name);
        });
        var service=new SuggestionSubmissionService((a,g,s,action)->{},context,repo,(a,g,c,s,q,op)->{operations.add(op);return new SubmissionRef("15",1);});
        var command=new SubmitCommand("12",2,"submit");assertThrows(IllegalStateException.class,()->service.submit(fixture.actor,"4",command));
        assertEquals(new SubmissionRef("15",1),service.submit(fixture.actor,"4",command));assertEquals(operations.get(0),operations.get(1));
        assertEquals(new SubmissionRef("15",1),service.submit(fixture.actor,"4",command));assertEquals(2,operations.size(),"existing receipt avoids another proposal invocation");
        assertThrows(ExtractionException.class,()->service.submit(fixture.actor,"4",new SubmitCommand("12",2,"different")));
        assertThrows(ExtractionException.class,()->service.submit(fixture.actor,"4",new SubmitCommand("12",1,"submit")));
    }
}

package vip.mate.semantic.application.extraction;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import vip.mate.semantic.core.identity.*;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.fact.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static vip.mate.semantic.application.extraction.ExtractionPorts.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionCoordinatorTest {
    final Actor actor=new Actor("1","2");
    final Instant now=Instant.parse("2026-09-08T00:00:00Z");
    final GraphScope scope=new GraphScope(new WorkspaceId("1"),new KnowledgeBaseId("3"),new GraphId("4"));
    final OntologyRevision ontology=OwlExtractionFixtures.ontology();
    final ModelConfiguration config=new ModelConfiguration("7","test","config",Map.of());
    @SuppressWarnings("unchecked") static <T>T port(Class<T> type,BiFunction<String,Object[],Object> handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->handler.apply(m.getName(),a));}
    final class Harness {
        Task task;Attempt attempt;List<Suggestion> results;Failure failure;boolean lease=true;String explanation;int progress;
        ExtractionTaskRepository repository=port(ExtractionTaskRepository.class,(name,args)->switch(name){
            case "insertOrReplay"->{task=(Task)args[0];yield task;}
            case "claim"->{attempt=new Attempt("8",task.taskId(),1,new Lease(task.taskId(),"worker",1,now.plusSeconds(60)),config,new Usage(0,0),now,null,null);yield Optional.of(attempt);}
            case "findClaimed"->task;case "heartbeat"->lease;
            case "progress"->{progress=(int)args[1];yield lease;}
            case "complete"->{results=(List<Suggestion>)args[2];explanation=args.length>4?(String)args[4]:null;yield true;}
            case "fail"->{failure=((Attempt)args[1]).failure();yield true;}
            default->throw new AssertionError(name);
        });
        ContextPort context=port(ContextPort.class,(name,args)->switch(name){case "ontology"->ontology;case "scope"->scope;case "configuration"->config;case "requireSnapshot"->null;default->throw new AssertionError(name);});
        ExtractionCoordinator coordinator(String text,ExtractionModelPort model,Clock clock,BooleanSupplier enabled){return new ExtractionCoordinator((a,g,s)->new SourceSnapshotInput("9",s,"digest",text,Map.of()),(a,g,s,action)->{},context,repository,model,clock,enabled,OwlExtractionFixtures.VALID);}
    }
    @Test void zeroOutputPublishesSuccessExplanationAndProgress(){Harness h=new Harness();var c=h.coordinator("source",r->new ModelResult(List.of(),new Usage(2,0),"No facts"),Clock.fixed(now,ZoneOffset.UTC),()->true);c.start(actor,new StartCommand("4","10","7","op"));assertTrue(c.runNext("worker"));assertEquals(List.of(),h.results);assertEquals("No facts",h.explanation);assertEquals(1,h.progress);assertNull(h.failure);}
    @Test void exactUniqueFallbackUsesCodePointsAndDoesNotGuessRepeatedQuotes(){
        assertEquals(new Quote(101,103,"😀乙"),ExtractionCoordinator.locate(new Chunk(1,100,"甲😀乙丙"),new Quote(99,100,"😀乙")));
        assertEquals(new Quote(-1,-1,"甲"),ExtractionCoordinator.locate(new Chunk(0,0,"甲乙甲"),new Quote(8,9,"甲")));
        assertEquals(new Quote(2,3,"甲"),ExtractionCoordinator.locate(new Chunk(0,0,"甲乙甲"),new Quote(2,3,"甲")));
        assertEquals(new Quote(-1,-1,"伪造"),ExtractionCoordinator.locate(new Chunk(0,0,"甲乙"),new Quote(0,2,"伪造")));
    }
    @Test void lostLeaseMakesNoModelCall(){Harness h=new Harness();AtomicInteger calls=new AtomicInteger();var c=h.coordinator("source",r->{calls.incrementAndGet();return new ModelResult(List.of(),new Usage(0,0),"");},Clock.fixed(now,ZoneOffset.UTC),()->true);c.start(actor,new StartCommand("4","10","7","op"));h.lease=false;c.runNext("worker");assertEquals(0,calls.get());assertNull(h.results);assertEquals("LEASE_LOST",h.failure.code());}
    @Test void quoteCannotEscapeItsModelChunk(){Harness h=new Harness();String text="甲".repeat(6001);AtomicInteger chunks=new AtomicInteger();var c=h.coordinator(text,r->{chunks.incrementAndGet();return new ModelResult(List.of(new RawSuggestion(new ObjectMention(OwlExtractionFixtures.SUBJECT,Set.of(OwlExtractionFixtures.TYPE),"M"),OwlExtractionFixtures.assertion(),null,Validity.unknown(),List.of(new Quote(5999,6001,"甲甲")))),new Usage(1,1),"");},Clock.fixed(now,ZoneOffset.UTC),()->true);c.start(actor,new StartCommand("4","10","7","op"));c.runNext("worker");assertEquals(2,chunks.get());assertNotNull(h.results);assertTrue(h.results.getFirst().diagnostics().stream().anyMatch(v->v.code().equals("QUOTE_MISMATCH")));}
    @Test void disabledFeatureAndTooLargeSourceNeverCallModel(){Harness h=new Harness();AtomicInteger calls=new AtomicInteger();ExtractionModelPort model=r->{calls.incrementAndGet();return new ModelResult(List.of(),new Usage(0,0),"");};var disabled=h.coordinator("source",model,Clock.fixed(now,ZoneOffset.UTC),()->false);assertThrows(ExtractionException.class,()->disabled.start(actor,new StartCommand("4","10","7","op")));assertFalse(disabled.runNext("worker"));var tooLarge=h.coordinator("甲".repeat(100001),model,Clock.fixed(now,ZoneOffset.UTC),()->true);assertThrows(ExtractionException.class,()->tooLarge.start(actor,new StartCommand("4","10","7","op")));assertEquals(0,calls.get());}
}

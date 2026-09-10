package vip.mate.semantic;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import vip.mate.semantic.extraction.JdbcExtractionRepository;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.core.identity.SemanticIds.*;
import java.time.Instant;
import java.util.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticExtractionLeaseIntegrationTest {
    JdbcExtractionRepository repository;
    DriverManagerDataSource data;
    final Instant now=Instant.parse("2026-09-08T00:00:00Z");
    static final String SUBJECT="urn:mateclaw:extraction:subject";
    static final String TYPE="urn:test:Equipment";
    static final String ASSERTION="DataPropertyAssertion(<urn:test:voltage> <urn:mateclaw:extraction:subject> \"220\"^^<http://www.w3.org/2001/XMLSchema#decimal>)";
    @BeforeEach void database(){
        data=new DriverManagerDataSource("jdbc:h2:mem:extraction_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V197__semantic_extraction.sql")).execute(data);
        repository=new JdbcExtractionRepository(new JdbcTemplate(data),new DataSourceTransactionManager(data));
    }
    Task task(String id,String workspace){
        var document=OntologyDocument.fromText("7","6","urn:test:ontology",Optional.empty(),OntologyDocumentSyntax.FUNCTIONAL,
            "Ontology(<urn:test:ontology> Declaration(Class(<urn:test:Equipment>)) Declaration(DataProperty(<urn:test:voltage>)))",LockedImport.digest(List.of()));
        var ontology=new OntologyRevision(new OntologyRevisionId("6"),new OntologyId("7"),1,
            new ParsedOntologyDocument(document,"urn:test:ontology",Optional.empty(),List.of(),List.of(),List.of(),List.of()),
            new BusinessPolicySet("v1",List.of()));
        return new Task(id,new Actor(workspace,"1"),"3","op-"+id,"hash-"+id,
            new SourceSnapshotInput("4","5","digest","甲😀乙",Map.of("title","fixture")),
            ontology,"definition","v1",new ModelConfiguration("8","fixture","hash",Map.of()),TaskStatus.QUEUED,1,false,now,now);}
    @Test void replayAndScopeArePersisted(){Task t=task("1","10");repository.insertOrReplay(t);assertEquals(t,repository.insertOrReplay(t));assertTrue(repository.find(new Actor("11","1"),"3","1").isEmpty());assertEquals("甲😀乙",repository.find(t.actor(),"3","1").orElseThrow().source().text());}
    @Test void expiredWorkerCannotPublishAndRestartRecovers(){repository.insertOrReplay(task("1","10"));Attempt old=repository.claim("old",now,now.plusSeconds(60),2,4).orElseThrow();assertTrue(repository.claim("other",now,now.plusSeconds(60),2,4).isEmpty());Attempt fresh=repository.claim("new",now.plusSeconds(61),now.plusSeconds(121),2,4).orElseThrow();assertEquals(old.lease().generation()+1,fresh.lease().generation());assertFalse(repository.complete(old.lease(),old,List.of(),now.plusSeconds(62)));assertTrue(repository.complete(fresh.lease(),fresh,List.of(),now.plusSeconds(62)));assertEquals(TaskStatus.SUCCEEDED,repository.require("1").status());}
    @Test void cancellationDiscardsLateResult(){Task t=task("1","10");repository.insertOrReplay(t);var attempt=repository.claim("worker",now,now.plusSeconds(60),2,4).orElseThrow();Task running=repository.require("1");Task cancelled=new Task(running.taskId(),running.actor(),running.graphId(),running.operationId(),running.requestHash(),running.source(),running.ontology(),running.definitionHash(),running.promptVersion(),running.configuration(),TaskStatus.CANCELLED,running.version()+1,true,running.createdAt(),now);assertTrue(repository.update(cancelled,running.version()));assertFalse(repository.complete(attempt.lease(),attempt,List.of(),now.plusSeconds(1)));assertEquals(TaskStatus.CANCELLED,repository.require("1").status());}
    @Test void capacityGateEnforcesWorkspaceAndInstanceLimits(){for(int i=1;i<=6;i++)repository.insertOrReplay(task(""+i,i<=3?"10":"11"));for(int i=0;i<4;i++)assertTrue(repository.claim("worker"+i,now,now.plusSeconds(60),2,4).isPresent());assertTrue(repository.claim("fifth",now,now.plusSeconds(60),2,4).isEmpty());}
    @Test void editWaitsForSubmissionGateAndCannotOvertakeReservedRevision()throws Exception{
        Task task=task("1","10");repository.insertOrReplay(task);Attempt attempt=repository.claim("worker",now,now.plusSeconds(60),2,4).orElseThrow();
        var raw=new RawSuggestion(new ObjectMention(SUBJECT,Set.of(TYPE),"machine"),new vip.mate.semantic.owl.OwlAssertionAdapter().parse(ASSERTION),null,vip.mate.semantic.core.fact.Validity.unknown(),List.of(new Quote(0,1,"甲")));
        var suggestion=new Suggestion("2","1",attempt.attemptId(),raw,null,List.of(),1,SuggestionStatus.OPEN);
        assertTrue(repository.complete(attempt.lease(),attempt,List.of(suggestion),now.plusSeconds(1)));
        try(var connection=data.getConnection();var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            connection.setAutoCommit(false);connection.createStatement().executeQuery("SELECT id FROM mate_semantic_extraction_gate WHERE id=1 FOR UPDATE");
            var entered=new java.util.concurrent.CountDownLatch(1);
            var edit=executor.submit(()->{entered.countDown();return repository.edit(task.actor(),"3",new Suggestion("2","1",attempt.attemptId(),raw,null,List.of(),2,SuggestionStatus.OPEN),1);});
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->edit.get(150,java.util.concurrent.TimeUnit.MILLISECONDS),"edit must respect same gate as submission reservation");
            connection.createStatement().executeUpdate("INSERT INTO mate_semantic_extraction_submission_intent(suggestion_id,edit_version,graph_id,operation_id,request_hash) VALUES('2',1,'3','submit','hash')");connection.commit();
            assertThrows(java.util.concurrent.ExecutionException.class,()->edit.get(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,repository.suggestion(task.actor(),"3","2").orElseThrow().editVersion());
        }
    }

    @Test void expiredThirdAttemptBecomesFailedInsteadOfQueuedForever(){repository.insertOrReplay(task("1","10"));for(int i=0;i<3;i++)assertTrue(repository.claim("worker",now.plusSeconds(i*61),now.plusSeconds(i*61+60),2,4).isPresent());assertTrue(repository.claim("worker",now.plusSeconds(183),now.plusSeconds(243),2,4).isEmpty());assertEquals(TaskStatus.FAILED,repository.require("1").status());}
}

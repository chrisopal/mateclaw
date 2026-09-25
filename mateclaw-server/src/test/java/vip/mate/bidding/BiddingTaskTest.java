package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.mockito.Mockito;

@Import(BiddingTaskTest.HandlerConfig.class)
class BiddingTaskTest extends BiddingHttpFixture {
    @Autowired BiddingRepository repository;
    @Autowired BiddingTaskService tasks;
    @MockBean BiddingEmployeeBindings employees;
    @MockBean BiddingDependencies dependencies;
    @MockBean BiddingSourceService sources;
    @Autowired TestResultHandler resultHandler;
    @Autowired BiddingFakeRuntime fakeRuntime;
    @Autowired ConfigurableApplicationContext context;

    @BeforeEach void clearStaleTestClaims() {
        biddingProperties.setEnabled(true); biddingProperties.setSchedulerEnabled(false);
        fakeRuntime.reset();
        Mockito.doNothing().when(fakeRuntime.bindings()).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(fakeRuntime.bindings().modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        jdbc.update("DELETE FROM mate_bidding_attempt");
        jdbc.update("DELETE FROM mate_bidding_task");
    }

    @Test void threeTransientFailuresPersistAttemptsAndFourthClaimIsNotMade() {
        String id=queuedTask("42");
        String sibling=queuedTask("42");
        // Select this isolated row explicitly first; the sibling remains independently queued.
        jdbc.update("UPDATE mate_bidding_task SET created_at=DATEADD('SECOND',-5,CURRENT_TIMESTAMP) WHERE id=?",id);
        for(int attempt=1;attempt<=3;attempt++) {
            var claims=repository.claimDue(Instant.now(),"retry-boot",1);
            var claim=claims.stream().filter(c->c.taskId().equals(id)).findFirst().orElseThrow();
            assertEquals(attempt,claim.attemptNo());
            tasks.complete(claim,failure("TRANSIENT"));
            assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,sibling));
            String status=jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,id);
            if(attempt<3) {
                assertEquals("WAITING_RETRY",status);
                jdbc.update("UPDATE mate_bidding_task SET next_run_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",id);
            } else assertEquals("FAILED",status);
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,id));
        assertFalse(repository.claimDue(Instant.now().plusSeconds(3600),"retry-boot",2).stream().anyMatch(c->c.taskId().equals(id)));
    }

    @Test void schedulerFakeRuntimePersistsThreeTransientAttemptsThenManualRetryStartsNewCycle() throws Exception {
        var project=project(); String actor=actorId("member"), projectId=project.path("id").asText();
        String task=queuedTaskInProject(projectId,actor);
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(fakeRuntime.bindings()).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(fakeRuntime.bindings().modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        fakeRuntime.enqueue(failure("TRANSIENT")); fakeRuntime.enqueue(failure("TRANSIENT")); fakeRuntime.enqueue(failure("TRANSIENT"));
        biddingProperties.setSchedulerEnabled(true);
        AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
        var scheduler=new BiddingScheduler(tasks,sources,biddingProperties,Runnable::run,"fake-runtime-boot");

        for(int attempt=1;attempt<=3;attempt++) {
            assertEquals(1,scheduler.dispatchDue(now.get()));
            assertEquals(attempt,fakeRuntime.calls());
            assertEquals(attempt,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,task));
            if(attempt<3) now.set(now.get().plusSeconds(40));
        }
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals(0,scheduler.dispatchDue(now.get().plusSeconds(3600)));
        assertEquals(3,fakeRuntime.calls());

        var scope=new BiddingTypes.Scope(workspace,actor,projectId);
        assertEquals("QUEUED",tasks.retry(scope,taskCommand("fake-manual-retry",task)).path("status").asText());
        fakeRuntime.enqueue(new BiddingTypes.Execution(json.createObjectNode().put("accepted",true),null,"a".repeat(64),"b".repeat(64),null));
        assertEquals(1,scheduler.dispatchDue(now.get()));
        assertEquals(4,fakeRuntime.calls());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,task));
        assertEquals(4,jdbc.queryForObject("SELECT MAX(attempt_no) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT cycle_no FROM mate_bidding_task WHERE id=?",Integer.class,task));
    }

    @Test void schedulerPersistsUncheckedRuntimeFailureImmediatelyWithoutRetryingModel() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(fakeRuntime.bindings()).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(fakeRuntime.bindings().modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        biddingProperties.setSchedulerEnabled(true);
        var scheduler=new BiddingScheduler(tasks,sources,biddingProperties,Runnable::run,"runtime-failure-boot");

        assertEquals(1,scheduler.dispatchDue(Instant.now()));

        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals(1,fakeRuntime.calls());
        String error=jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=?",String.class,task);
        var diagnostic=json.readTree(error);
        assertTrue(diagnostic.path("resultUnknown").asBoolean());
        assertFalse(diagnostic.path("stopped").asBoolean());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE status='WAITING_RETRY' AND id=?",Integer.class,task));
    }

    @Test void malformedEarliestCandidateFailsWithDiagnosticAndDoesNotPoisonValidCandidate() {
        String malformed=queuedTask("42"), missingPackage=queuedTask("42"), valid=queuedTask("42");
        jdbc.update("UPDATE mate_bidding_task SET input_json='not-json',created_at=DATEADD('SECOND',-5,CURRENT_TIMESTAMP) WHERE id=?",malformed);
        jdbc.update("UPDATE mate_bidding_task SET skill_package_id='missing-pinned-package',created_at=DATEADD('SECOND',-4,CURRENT_TIMESTAMP) WHERE id=?",missingPackage);

        var claims=repository.claimDue(Instant.now(),"malformed-candidate-boot",2);

        assertEquals(1,claims.size()); assertEquals(valid,claims.getFirst().taskId());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,malformed));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=?",String.class,malformed).contains("TASK_SNAPSHOT_INVALID"));
        assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM mate_bidding_task WHERE id=?",Integer.class,malformed));
        assertEquals(1,jdbc.queryForObject("SELECT attempt_no FROM mate_bidding_attempt WHERE task_id=?",Integer.class,malformed));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,missingPackage));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=?",String.class,missingPackage).contains("TASK_SNAPSHOT_INVALID"));
        assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM mate_bidding_task WHERE id=?",Integer.class,missingPackage));
    }

    @Test void uncheckedResultHandlerProgrammingFailureIsPersistedAsRejectionWithoutHandlerRetry() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        resultHandler.throwProgrammingFailure();
        var claim=repository.claimDue(Instant.now(),"handler-runtime-boot",1).getFirst();

        tasks.complete(claim,new BiddingTypes.Execution(json.createObjectNode().put("accepted",true),null,"a".repeat(64),"b".repeat(64),null));

        assertEquals(1,resultHandler.calls());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        String diagnostic=jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId());
        assertTrue(diagnostic.contains("RESULT_HANDLER_FAILURE"));
        assertTrue(jdbc.queryForObject("SELECT rejected_output FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()).contains("accepted"));
    }

    @Test void completionDatabaseRetriesReuseAcceptedExecutionWithoutCallingModelAgain() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model");
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        var claim=repository.claimDue(Instant.now(),"completion-boot",1).getFirst();
        var execution=new BiddingTypes.Execution(json.createObjectNode().put("accepted",true),null,"a".repeat(64),"b".repeat(64),null);
        resultHandler.failBeforeAccept(3);

        tasks.complete(claim,execution);

        assertEquals(4,resultHandler.calls());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()));
        assertTrue(jdbc.queryForObject("SELECT output_json FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()).contains("accepted"));
        var details=tasks.taskDetails(new BiddingTypes.Scope(workspace,actor,project.path("id").asText()),task);
        assertEquals("candidate",details.path("effectiveResult").path("ref").path("kind").asText());
        assertTrue(details.path("effectiveResult").path("payload").path("accepted").asBoolean());
    }

    @Test void enqueuePersistsAuthenticatedActorAndReplaysOneTransactionalOperation() throws Exception {
        var project=project(); String actor=actorId("member"), id=project.path("id").asText();
        long skillId=90_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000);
        String skill=Long.toString(skillId), packageId=UUID.randomUUID().toString(), pinDigest="c".repeat(64), config="b".repeat(64);
        resultHandler.registerSkill(skill);
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skillId,"bidding-tender-profile",Long.valueOf(workspace));
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            packageId,workspace,id,skill,"v1",pinDigest,"{\"SKILL.md\":\"pinned\"}");
        var stored=(com.fasterxml.jackson.databind.node.ObjectNode)project.deepCopy();
        var analyst=stored.putObject("bindings").putObject("analyst"); analyst.put("agentId","employee"); analyst.put("configDigest",config);
        analyst.putArray("skillPins").addObject().put("skillId",skill).put("digest",pinDigest);
        long unhandledId=skillId+2_000_000L; String unhandled=Long.toString(unhandledId), unhandledPackage=UUID.randomUUID().toString(), unhandledDigest="d".repeat(64);
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",unhandledId,"bidding-tender-profile",Long.valueOf(workspace));
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            unhandledPackage,workspace,id,unhandled,"v1",unhandledDigest,"{\"SKILL.md\":\"unhandled\"}");
        analyst.withArray("skillPins").addObject().put("skillId",unhandled).put("digest",unhandledDigest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),id);
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.any(),Mockito.anyString())).thenReturn("model-id");
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        var scope=new BiddingTypes.Scope(workspace,actor,id);
        var refs=List.of(new BiddingTypes.Ref("sourceSet","current",1,"d".repeat(64)));
        var input=json.createObjectNode().put("actorId","model-supplied-user").put("prompt","analyze");
        var command=new BiddingTypes.Command("enqueue-op",null,"DISPATCH_ANALYSIS",json.createObjectNode());

        var first=tasks.enqueue(scope,command,skill,"profile-target",refs,input);
        var repeated=tasks.enqueue(scope,command,skill,"profile-target",refs,input);

        var missingHandler=assertThrows(BiddingApiException.class,()->tasks.enqueue(scope,
            new BiddingTypes.Command("unhandled-op",null,"DISPATCH_ANALYSIS",json.createObjectNode()),unhandled,"profile-target",refs,input));
        assertEquals("SKILL_HANDLER_MISSING",missingHandler.code());

        assertEquals(first,repeated);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE id=?",Integer.class,first.path("taskId").asText()));
        assertEquals(actor,jdbc.queryForObject("SELECT actor_id FROM mate_bidding_task WHERE id=?",String.class,first.path("taskId").asText()));
        var claim=repository.claimDue(Instant.now(),"enqueue-boot",1).getFirst();
        assertEquals(actor,claim.scope().actorId());
        assertEquals("model-id",claim.modelConfigId());
        assertEquals("model-supplied-user",claim.input().path("actorId").asText());
        Mockito.verify(dependencies,Mockito.times(1)).validate(Mockito.eq(scope),Mockito.eq(refs));
    }

    @Test void manualRetryKeepsAttemptHistoryAndContinuesGlobalAttemptNumber() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        var scope=new BiddingTypes.Scope(workspace,actor,project.path("id").asText());
        var initial=repository.claimDue(Instant.now(),"manual-boot",1).getFirst();
        tasks.complete(initial,failure("PERMANENT"));
        Mockito.doNothing().when(dependencies).validate(Mockito.any(),Mockito.anyList());
        Mockito.doNothing().when(employees).validate(Mockito.any(),Mockito.anyString(),Mockito.anyString());
        var command=taskCommand("manual-retry",task);
        assertEquals("QUEUED",tasks.retry(scope,command).path("status").asText());
        var retried=repository.claimDue(Instant.now(),"manual-boot",1).getFirst();
        assertEquals(2,retried.attemptNo());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,task));
    }

    @Test void cancellationInvalidatesClaimAndLateSuccessCannotRestoreIt() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        String queuedSibling=queuedTask("43");
        var scope=new BiddingTypes.Scope(workspace,actor,project.path("id").asText());
        var claim=repository.claimDue(Instant.now(),"cancel-boot",1).getFirst();
        var command=taskCommand("cancel",task);
        assertEquals("CANCELLED",tasks.cancel(scope,command).path("status").asText());
        assertEquals(queuedSibling,repository.claimDue(Instant.now(),"cancel-boot",1).getFirst().taskId());
        assertNull(jdbc.queryForObject("SELECT deadline_at FROM mate_bidding_task WHERE id=?",java.sql.Timestamp.class,task));
        var payload=json.createObjectNode().put("ok",true);
        tasks.complete(claim,new BiddingTypes.Execution(payload,null,"a".repeat(64),"b".repeat(64),null));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()));
    }

    @Test void revokedActorCannotCommitLateSuccessfulOutput() throws Exception {
        var project=project(); String actor=actorId("member"), task=queuedTaskInProject(project.path("id").asText(),actor);
        var claim=repository.claimDue(Instant.now(),"revoke-boot",1).getFirst();
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE id=?",Long.valueOf(actor));
        var payload=json.createObjectNode().put("ok",true);
        tasks.complete(claim,new BiddingTypes.Execution(payload,null,"a".repeat(64),"b".repeat(64),null));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE id=?",String.class,claim.attemptId()).contains("UNAUTHENTICATED"));
    }

    @Test void concurrentDatabaseClaimsProduceOnlyOneClaimAndAttempt() throws Exception {
        String id=queuedTask("42");
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await(); return repository.claimDue(Instant.now(),"boot-a",1);});
            var second=pool.submit(()->{start.await(); return repository.claimDue(Instant.now(),"boot-b",1);});
            start.countDown();
            var claims=new java.util.ArrayList<BiddingTypes.Claim>(first.get());
            claims.addAll(second.get());
            assertEquals(1,claims.size());
            assertEquals(id,claims.getFirst().taskId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",Integer.class,id));
            assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,id));
        }
    }

    @Test void claimBatchAllowsOnlyOneTaskPerWorkspace() throws Exception {
        var project=project(); String projectId=project.path("id").asText(), actor=actorId("member");
        String first=queuedTaskInProject(projectId,actor), second=queuedTaskInProject(projectId,actor);
        var claims=repository.claimDue(Instant.now(),"workspace-boot",2);
        assertEquals(1,claims.size());
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,claims.getFirst().taskId()));
        String other=claims.getFirst().taskId().equals(first)?second:first;
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,other));
    }

    @Test void repositoryFillsSecondGlobalSlotWhenOneTaskAlreadyRuns() throws Exception {
        var firstProject=project(); String first=queuedTaskInProject(firstProject.path("id").asText(),actorId("member"));
        var otherProject=api("POST","/projects","owner",otherWorkspace,Map.of("operationId",UUID.randomUUID().toString(),"name","Other project","lotName","Lot"),200);
        String second=queuedTaskInWorkspace(otherWorkspace,otherProject.path("id").asText(),actorId("owner"));

        assertEquals(first,repository.claimDue(Instant.now(),"capacity-boot",1).getFirst().taskId());
        var secondClaim=repository.claimDue(Instant.now(),"capacity-boot",2);
        assertEquals(1,secondClaim.size(),"second status="+jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,second)
            +", workspace="+jdbc.queryForObject("SELECT workspace_id FROM mate_bidding_task WHERE id=?",String.class,second)
            +", workspaceRows="+jdbc.queryForObject("SELECT COUNT(*) FROM mate_workspace WHERE id=?",Integer.class,Long.valueOf(otherWorkspace))
            +", running="+jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE status='RUNNING'",Integer.class));
        assertEquals(second,secondClaim.getFirst().taskId());
    }

    @Test void claimReconstructsActorOnlyFromPersistedServerActorColumn() {
        String id=queuedTask("42");
        var claim=repository.claimDue(Instant.now(),"boot",1).getFirst();
        assertEquals("42",claim.scope().actorId());
        assertEquals(id,claim.taskId());
    }

    @Test void legacyTaskWithoutActorFailsClosedWithPersistedDiagnostic() {
        String id=queuedTask(null);
        assertTrue(repository.claimDue(Instant.now(),"boot",1).isEmpty());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM mate_bidding_task WHERE id=?",Integer.class,id));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=?",String.class,id).contains("TASK_ACTOR_MISSING"));
    }

    @Test void taskWithInvalidWorkspaceCannotRunWithoutWorkspaceLock() {
        String id=queuedTask("42"), invalidWorkspace=UUID.randomUUID().toString();
        jdbc.update("UPDATE mate_bidding_skill_package SET workspace_id=? WHERE id=(SELECT skill_package_id FROM mate_bidding_task WHERE id=?)",invalidWorkspace,id);
        jdbc.update("UPDATE mate_bidding_task SET workspace_id=? WHERE id=?",invalidWorkspace,id);
        assertTrue(repository.claimDue(Instant.now(),"boot",1).isEmpty());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM mate_bidding_task WHERE id=?",Integer.class,id));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=?",String.class,id).contains("TASK_WORKSPACE_MISSING"));
    }

    @Test void scannerReadsTwoSourcesAndUsesInjectedSynchronousExecutor() {
        String task=queuedTask("42"); biddingProperties.setSchedulerEnabled(true);
        var scheduler=new BiddingScheduler(tasks,sources,biddingProperties,Runnable::run,"sync-scan-boot");
        assertEquals(1,scheduler.dispatchDue(Instant.now()));
        Mockito.verify(sources).readPending(2);
        assertFalse(repository.isTaskRunning(task));
    }

    @Test void scannerContinuesSourceReadsWhenAllDatabaseModelSlotsAreOccupied() throws Exception {
        String first=queuedTaskInWorkspace(workspace,UUID.randomUUID().toString(),actorId("member"));
        String second=queuedTaskInWorkspace(otherWorkspace,UUID.randomUUID().toString(),actorId("owner"));
        var active=repository.claimDue(Instant.now(),"full-scan-boot",2);
        assertEquals(2,active.size());

        biddingProperties.setSchedulerEnabled(true);
        var scheduler=new BiddingScheduler(tasks,sources,biddingProperties,Runnable::run,"full-scan-boot");
        assertEquals(0,scheduler.dispatchDue(Instant.now()));
        Mockito.verify(sources).readPending(2);
        assertTrue(repository.isTaskRunning(first)); assertTrue(repository.isTaskRunning(second));
    }

    @Test void duplicateResultHandlerRegistrationFailsValidation() {
        var factory=(org.springframework.beans.factory.support.DefaultListableBeanFactory)context.getBeanFactory();
        factory.registerSingleton("duplicateBiddingResultHandler",new BiddingResultHandler() {
            @Override public java.util.Set<String> skillIds() { return java.util.Set.of("skill-1"); }
            @Override public BiddingTypes.Ref accept(BiddingTypes.Claim claim,com.fasterxml.jackson.databind.node.ObjectNode payload) { return null; }
        });
        try { assertThrows(IllegalStateException.class,tasks::validateResultHandlers); }
        finally { factory.destroySingleton("duplicateBiddingResultHandler"); }
    }

    @Test void authorizedTaskListIncludesSkillNamesFromWorkspaceAndProjectScopedPackages() throws Exception {
        var project=project(); String projectId=project.path("id").asText(), actor=actorId("member");
        long skillId=95_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000);
        long otherSkillId=skillId+2_000_000L;
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skillId,"bidding-tender-profile",Long.valueOf(workspace));
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",otherSkillId,"private-other-project-skill",Long.valueOf(workspace));
        String validPackage=UUID.randomUUID().toString(),wrongProjectPackage=UUID.randomUUID().toString(),wrongWorkspacePackage=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",validPackage,workspace,projectId,Long.toString(skillId),"v1","a".repeat(64),"{}");
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",wrongProjectPackage,workspace,UUID.randomUUID().toString(),Long.toString(otherSkillId),"v1","b".repeat(64),"{}");
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",wrongWorkspacePackage,otherWorkspace,projectId,Long.toString(skillId),"v1","c".repeat(64),"{}");
        String validTask=listedTask(projectId,actor,validPackage),wrongProjectTask=listedTask(projectId,actor,wrongProjectPackage),wrongWorkspaceTask=listedTask(projectId,actor,wrongWorkspacePackage);

        var page=api("GET","/projects/"+projectId+"/tasks?page=1&pageSize=10","viewer",workspace,null,200);
        var items=page.path("items");
        assertEquals("bidding-tender-profile",listedSkill(items,validTask));
        assertTrue(listedSkill(items,wrongProjectTask)==null || listedSkill(items,wrongProjectTask).isBlank());
        assertTrue(listedSkill(items,wrongWorkspaceTask)==null || listedSkill(items,wrongWorkspaceTask).isBlank());
    }

    private String listedTask(String project,String actor,String packageId) {
        String task=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",task,workspace,project,actor,"employee",packageId,"d".repeat(64),"{\"input\":{\"prompt\":\"fixed\"}}","[]");
        return task;
    }

    private String listedSkill(com.fasterxml.jackson.databind.JsonNode items,String taskId) {
        for(var item:items) if(taskId.equals(item.path("taskId").asText())) return item.path("skillId").isNull()?null:item.path("skillId").asText();
        return null;
    }

    @Test void taskListAndDetailAreScopedAndExposeSnapshotAttemptsWithoutClaimSecrets() throws Exception {
        var project=project(); String projectId=project.path("id").asText(), task=queuedTaskInProject(projectId,actorId("member"));
        var claim=repository.claimDue(Instant.now(),"readback-boot",1).getFirst();
        tasks.complete(claim,failure("VALIDATION"));

        var page=api("GET","/projects/"+projectId+"/tasks?page=1&pageSize=10","viewer",workspace,null,200);
        assertEquals(1,page.path("total").asInt()); assertEquals(task,page.path("items").get(0).path("taskId").asText());
        var detail=api("GET","/tasks/"+task,"viewer",workspace,null,200);
        assertEquals("FAILED",detail.path("status").asText());
        assertFalse(detail.path("snapshot").path("_bidding").has("modelConfigId"));
        assertEquals("fixed",detail.path("snapshot").path("input").path("prompt").asText());
        assertEquals(1,detail.path("attempts").size());
        assertEquals("FAILED",detail.path("attempts").get(0).path("state").asText());
        assertTrue(detail.path("attempts").get(0).path("rejection").path("code").asText().contains("TEST_FAILURE"));
        assertTrue(detail.path("effectiveResult").isNull());
        assertFalse(detail.toString().contains(claim.token()));
        api("GET","/tasks/"+task,"owner",otherWorkspace,null,404);
    }

    private String queuedTask(String actor) {
        String task=UUID.randomUUID().toString(), packageId=UUID.randomUUID().toString(), project=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                packageId,workspace,project,"skill-1","v1","a".repeat(64),"{\"SKILL.md\":\"test\"}");
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                task,workspace,project,actor,"employee",packageId,"b".repeat(64),"{\"input\":{\"prompt\":\"fixed\"}}","[]");
        return task;
    }

    private String queuedTaskInProject(String project,String actor) {
        return queuedTaskInWorkspace(workspace,project,actor);
    }

    private String queuedTaskInWorkspace(String workspaceId,String project,String actor) {
        String task=UUID.randomUUID().toString(), packageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                packageId,workspaceId,project,"skill-1","v1","a".repeat(64),"{\"SKILL.md\":\"test\"}");
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                task,workspaceId,project,actor,"employee",packageId,"b".repeat(64),"{\"_bidding\":{\"modelConfigId\":\"model\",\"targetId\":\"target\"},\"input\":{\"prompt\":\"fixed\"}}","[]");
        return task;
    }

    private String actorId(String role) {
        String username=auth.parseToken(tokens.get(role).substring(7));
        return jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?",String.class,username);
    }

    private BiddingTypes.Command taskCommand(String operation,String task) {
        var payload=json.createObjectNode().put("taskId",task);
        return new BiddingTypes.Command(operation,null,"CANCEL_TASK",payload);
    }

    private BiddingTypes.Execution failure(String category) {
        return new BiddingTypes.Execution(null,new BiddingTypes.Failure("TEST_FAILURE",category,null,false,false,false),null,null,"rejected");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class HandlerConfig {
        @Bean TestResultHandler testResultHandler() { return new TestResultHandler(); }
        @Bean @org.springframework.context.annotation.Primary BiddingFakeRuntime fakeRuntime(BiddingAccess access,BiddingDependencies dependencies,
                org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new BiddingFakeRuntime(access,dependencies,Mockito.mock(BiddingEmployeeBindings.class),jdbc,Mockito.mock(vip.mate.agent.AgentService.class),
                Mockito.mock(vip.mate.workspace.conversation.ConversationService.class),Mockito.mock(vip.mate.agent.repository.AgentMapper.class));
        }
    }

    static final class TestResultHandler implements BiddingResultHandler {
        private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger failingCalls=new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean programmingFailure=new java.util.concurrent.atomic.AtomicBoolean();
        void failBeforeAccept(int count) { failingCalls.set(count); calls.set(0); }
        void throwProgrammingFailure() { calls.set(0); programmingFailure.set(true); }
        int calls() { return calls.get(); }
        private final java.util.Set<String> registered=new java.util.concurrent.ConcurrentSkipListSet<>(java.util.Set.of("skill-1"));
        void registerSkill(String id) { registered.add(id); }
        @Override public java.util.Set<String> skillIds() { return java.util.Set.copyOf(registered); }
        @Override public BiddingTypes.Ref accept(BiddingTypes.Claim claim,com.fasterxml.jackson.databind.node.ObjectNode payload) {
            calls.incrementAndGet();
            if(programmingFailure.getAndSet(false)) throw new IllegalStateException("controlled handler programming failure");
            if(failingCalls.getAndUpdate(value->Math.max(0,value-1))>0)
                throw new org.springframework.dao.TransientDataAccessResourceException("controlled database retry");
            return new BiddingTypes.Ref("candidate",claim.input().path("_biddingTargetId").asText("target"),1,"e".repeat(64));
        }
    }
}

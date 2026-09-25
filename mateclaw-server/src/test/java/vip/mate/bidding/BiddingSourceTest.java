package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@Import(BiddingSourceTest.SchedulingTestConfig.class)
class BiddingSourceTest extends BiddingHttpFixture {
    @Configuration @EnableScheduling static class SchedulingTestConfig {}
    @org.springframework.beans.factory.annotation.Autowired BiddingSourceService sources;
    @org.springframework.beans.factory.annotation.Autowired BiddingDependencies dependencies;
    @org.springframework.beans.factory.annotation.Autowired BiddingProperties biddingProperties;

    @Test void uploadIsReadByProductionSchedulerWithoutManualReadCall() throws Exception {
        var project=project();
        biddingProperties.setSchedulerEnabled(true);
        try {
            var source=upload(project,UUID.randomUUID().toString(),"scheduled.pdf",readablePdf("Scheduled tender"),workspace,"member",200);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            String status="PENDING";
            while(System.nanoTime()<deadline && !"READY".equals(status)) {
                Thread.sleep(100);
                var listed=api("GET","/projects/"+project.path("id").asText()+"/sources","viewer",workspace,null,200);
                status="UNKNOWN";
                for(JsonNode item:listed) if(source.path("id").asText().equals(item.path("id").asText())) status=item.path("readStatus").asText();
            }
            assertEquals("READY",status,"source "+source.path("id").asText()+" must be consumed by scheduled worker");
        } finally { biddingProperties.setSchedulerEnabled(false); }
    }

    @Test void projectSourceCapacityHasExactHundredMibBoundary() {
        assertDoesNotThrow(()->BiddingSourceService.ensureProjectCapacity(100L*1024*1024-1,1));
        var error=assertThrows(BiddingApiException.class,()->BiddingSourceService.ensureProjectCapacity(100L*1024*1024,1));
        assertEquals(413,error.status()); assertEquals("PROJECT_SOURCE_LIMIT",error.code());
    }

    @Test void sourceReaderHasNoIndependentPollerAndStartupRecoveryHonorsBothSwitches() {
        var repository=org.mockito.Mockito.mock(BiddingRepository.class);
        var properties=new BiddingProperties();
        var tx=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var service=new BiddingSourceService(repository,org.mockito.Mockito.mock(BiddingAccess.class),org.mockito.Mockito.mock(BiddingSourceReader.class),
            org.mockito.Mockito.mock(BiddingDependencies.class),new com.fasterxml.jackson.databind.ObjectMapper(),tx,properties);
        service.recoverInterruptedReaders();
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).pendingSources(org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).recoverReadingSources(org.mockito.ArgumentMatchers.any());
        properties.setEnabled(true); // module on, worker deliberately disabled
        service.recoverInterruptedReaders();
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).pendingSources(org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).recoverReadingSources(org.mockito.ArgumentMatchers.any());
        service.readPending(2);
        org.mockito.Mockito.verify(repository).pendingSources(2);
    }

    @Test void uploadDoesNotRejectUnselectedLibraryBytesAtEffectiveCapacity() {
        var repository=org.mockito.Mockito.mock(BiddingRepository.class);
        var access=org.mockito.Mockito.mock(BiddingAccess.class);
        var reader=org.mockito.Mockito.mock(BiddingSourceReader.class);
        var dependencies=org.mockito.Mockito.mock(BiddingDependencies.class);
        var tx=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(tx.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var jsonMapper=new com.fasterxml.jackson.databind.ObjectMapper();
        org.mockito.Mockito.when(repository.findProject("7","p")).thenReturn(jsonMapper.createObjectNode());
        org.mockito.Mockito.when(repository.lockProject("7","p")).thenReturn(true);
        org.mockito.Mockito.when(repository.findOperation("7","9","op")).thenReturn(null);
        org.mockito.Mockito.when(repository.insertSourceHead(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        var service=new BiddingSourceService(repository,access,reader,dependencies,jsonMapper,tx,new BiddingProperties());
        assertDoesNotThrow(()->service.upload(new BiddingTypes.Scope("7","9","p"),"op","TENDER",null,new byte[]{1},"tender.pdf"));
        org.mockito.Mockito.verify(repository).insertSource(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    @Test void duplicateUploadIsIdempotentAndOriginalBytesReadBackByDigest() throws Exception {
        var project=project(); byte[] bytes=readablePdf("Tender score 12.50"); String operationId=UUID.randomUUID().toString();
        JsonNode first=upload(project,operationId,"tender.pdf",bytes,workspace, "member",200);
        JsonNode duplicate=upload(project,operationId,"tender.pdf",bytes,workspace,"member",200);
        assertEquals(first.path("id").asText(),duplicate.path("id").asText());
        assertEquals(sha256(bytes),first.path("sha256").asText());
        assertEquals(1,sources.readPending(20));
        String sourceId=first.path("id").asText();
        var response=mvc.perform(MockMvcRequestBuilders.get("/api/v1/bidding/projects/{id}/sources/{sourceId}/versions/1/content",project.path("id").asText(),sourceId)
            .header("Authorization",tokens.get("member")).header("X-Workspace-Id",workspace)).andReturn().getResponse();
        assertEquals(200,response.getStatus()); assertArrayEquals(bytes,response.getContentAsByteArray());
        assertEquals("no-store",response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Disposition").contains("tender.pdf"));
        var listing=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        assertEquals("READY",listing.get(0).path("readStatus").asText());
    }

    @Test void evidenceIsProjectScopedAndUsesServerStoredBlock() throws Exception {
        var project=project(); byte[] bytes=readablePdf("Tender score 12.50");
        JsonNode source=upload(project,UUID.randomUUID().toString(),"tender.pdf",bytes,workspace,"member",200);
        sources.readPending(2);
        JsonNode listed=api("GET","/projects/"+project.path("id").asText()+"/sources","viewer",workspace,null,200);
        String blockId=listed.get(0).path("blocks").get(0).path("id").asText();
        var evidence=api("GET","/projects/"+project.path("id").asText()+"/evidence?sourceId="+source.path("id").asText()+"&version=1&blockId="+blockId,"viewer",workspace,null,200);
        assertTrue(evidence.path("text").asText().contains("12.50"));
        api("GET","/projects/"+project.path("id").asText()+"/evidence?sourceId="+source.path("id").asText()+"&version=1&blockId="+blockId,"owner",otherWorkspace,null,404);
    }

    @Test void scannedPagesCannotBeConfirmedButBlankPagesCanBeExcludedWithReason() throws Exception {
        var scannedProject=project(); byte[] scan=scanPdf();
        var scanned=upload(scannedProject,UUID.randomUUID().toString(),"scan.pdf",scan,workspace,"member",200); sources.readPending(2);
        var scanRef=refFrom(scanned.path("ref"));
        command(scannedProject,ref(scannedProject),"CONFIRM_SOURCE_SET",Map.of("sourceRefs",List.of(scanRef),"exclusions",List.of()),"owner",422);

        var blankProject=project(); byte[] mixed=pdfWithBlankPage();
        var blank=upload(blankProject,UUID.randomUUID().toString(),"blank-page.pdf",mixed,workspace,"member",200); sources.readPending(2);
        var listing=api("GET","/projects/"+blankProject.path("id").asText()+"/sources","member",workspace,null,200);
        var blocks=listing.get(0).path("blocks");
        String emptyBlock=null;
        for(JsonNode block:blocks) if("EMPTY_PAGE".equals(block.path("kind").asText())) emptyBlock=block.path("id").asText();
        assertNotNull(emptyBlock);
        var payload=Map.of("sourceRefs",List.of(refFrom(blank.path("ref"))),
            "exclusions",List.of(Map.of("sourceRef",refFrom(blank.path("ref")),"blockId",emptyBlock,"reason","此页为空白页")));
        command(blankProject,ref(blankProject),"CONFIRM_SOURCE_SET",payload,"viewer",403);
        command(blankProject,ref(blankProject),"CONFIRM_SOURCE_SET",payload,"owner",200);
    }

    @Test void confirmationRequiresExpectedSourceSetHeadAndRejectsStaleWriter() throws Exception {
        var project=project(); var one=upload(project,UUID.randomUUID().toString(),"one.pdf",readablePdf("one"),workspace,"member",200); sources.readPending(2);
        var two=upload(project,UUID.randomUUID().toString(),"two.pdf",readablePdf("two"),workspace,"member",200); sources.readPending(2);
        var first=confirm(project,null,List.of(refFrom(one.path("ref"))));
        assertTrue(first.path("ref").path("version").asLong()>0);
        var stale=confirm(project,null,List.of(refFrom(two.path("ref"))),409);
        assertEquals("SOURCE_SET_HEAD_CONFLICT",stale.path("data").path("code").asText());
        var next=confirm(project,refFrom(first.path("ref")),List.of(refFrom(two.path("ref"))));
        assertEquals(first.path("ref").path("version").asLong()+1,next.path("ref").path("version").asLong());
    }

    @Test void concurrentConfirmationsWithSameHeadAllowOnlyOneWriter() throws Exception {
        var project=project(); var one=upload(project,UUID.randomUUID().toString(),"race-1.pdf",readablePdf("race one"),workspace,"member",200); sources.readPending(2);
        var two=upload(project,UUID.randomUUID().toString(),"race-2.pdf",readablePdf("race two"),workspace,"member",200); sources.readPending(2);
        var scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),project.path("id").asText());
        var gate=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(one,two).stream().map(source->pool.submit(()->{
                var payload=new java.util.LinkedHashMap<String,Object>(); payload.put("sourceRefs",List.of(refFrom(source.path("ref")))); payload.put("exclusions",List.of()); payload.put("expectedSourceSetRef",null);
                try { gate.await(); sources.confirmSet(scope,new BiddingTypes.Command(UUID.randomUUID().toString(),ref(project),"CONFIRM_SOURCE_SET",json.valueToTree(payload))); return "CONFIRMED"; }
                catch(BiddingApiException conflict) { return conflict.code(); }
            })).toList();
            gate.countDown();
            var outcomes=List.of(futures.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),futures.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("CONFIRMED"::equals).count(),outcomes.toString());
            assertEquals(1,outcomes.stream().filter("SOURCE_SET_HEAD_CONFLICT"::equals).count(),outcomes.toString());
        } finally { pool.shutdownNow(); }
    }

    @Test void selectedSourceBytesExcludeUnselectedAndSupersededVersions() throws Exception {
        var project=project(); var source=upload(project,UUID.randomUUID().toString(),"version-1.pdf",readablePdf("old version"),workspace,"member",200); sources.readPending(2);
        var unused=upload(project,UUID.randomUUID().toString(),"unused.pdf",readablePdf("not selected"),workspace,"member",200); sources.readPending(2);
        var updated=uploadSuperseding(project,UUID.randomUUID().toString(),"version-2.pdf",readablePdf("new version"),refFrom(source.path("ref")),workspace,"member",200); sources.readPending(2);
        var scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),project.path("id").asText());
        long selected=sources.effectiveSourceBytes(scope,List.of(refFrom(updated.path("ref"))));
        assertEquals(readablePdf("new version").length,selected);
        assertTrue(sources.effectiveSourceBytes(scope,List.of(refFrom(unused.path("ref")),refFrom(updated.path("ref"))))>selected);
        assertEquals("SOURCE_SET_DUPLICATE",assertThrows(BiddingApiException.class,
            ()->sources.effectiveSourceBytes(scope,List.of(refFrom(source.path("ref")),refFrom(updated.path("ref"))))).code());
        assertTrue(selected<100L*1024*1024);
        assertNotEquals(source.path("ref").path("version").asLong(),updated.path("ref").path("version").asLong());
        assertNotNull(unused.path("ref"));
        confirm(project,null,List.of(refFrom(updated.path("ref"))));
    }

    @Test void retryCannotEraseEvidenceFromPreviouslyConfirmedSourceSet() throws Exception {
        var project=project(); var source=upload(project,UUID.randomUUID().toString(),"fixed.pdf",pdfWithBlankPage(),workspace,"member",200); sources.readPending(2);
        var listed=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        String blockId=listed.get(0).path("blocks").findValues("id").getFirst().asText();
        // The only reviewable block is a pure empty page; confirm with an explicit reason.
        var blocks=listed.get(0).path("blocks");
        for(JsonNode block:blocks) if("EMPTY_PAGE".equals(block.path("kind").asText())) blockId=block.path("id").asText();
        var originalSet=confirm(project,null,List.of(refFrom(source.path("ref"))),List.of(Map.of("sourceRef",refFrom(source.path("ref")),"blockId",blockId,"reason","空白页")));
        var later=upload(project,UUID.randomUUID().toString(),"later.pdf",readablePdf("later source"),workspace,"member",200); sources.readPending(2);
        confirm(project,refFrom(originalSet.path("ref")),List.of(refFrom(later.path("ref"))));
        var payload=json.createObjectNode(); payload.set("sourceRef",json.valueToTree(refFrom(source.path("ref"))));
        var error=api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expected",ref(project),"action","RETRY_SOURCE_READ","payload",payload),409);
        assertEquals("SOURCE_ALREADY_CONFIRMED",error.path("data").path("code").asText());
        assertEquals("NEEDS_REVIEW",api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200).get(0).path("readStatus").asText());
        var evidence=api("GET","/projects/"+project.path("id").asText()+"/evidence?sourceId="+source.path("id").asText()+"&version=1&blockId="+blockId,"viewer",workspace,null,200);
        assertEquals(blockId,evidence.path("id").asText());
    }

    @Test void confirmedVersionIsImmutableButFailedSupersedingVersionCanBeRetried() throws Exception {
        var project=project(); var v1=upload(project,UUID.randomUUID().toString(),"v1.pdf",readablePdf("confirmed v1"),workspace,"member",200); sources.readPending(2);
        confirm(project,null,List.of(refFrom(v1.path("ref"))));
        byte[] corrupt=new byte[]{'%', 'P', 'D', 'F', '-', 'x'};
        var v2=uploadSuperseding(project,UUID.randomUUID().toString(),"v2.pdf",corrupt,refFrom(v1.path("ref")),workspace,"member",200);
        sources.readPending(2);
        assertEquals("FAILED",api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200).get(1).path("readStatus").asText());
        var v2Retry=json.createObjectNode(); v2Retry.set("sourceRef",json.valueToTree(refFrom(v2.path("ref"))));
        api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expected",ref(project),"action","RETRY_SOURCE_READ","payload",v2Retry),200);
        var afterRetry=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        assertEquals("PENDING",afterRetry.get(1).path("readStatus").asText());
        for(int attempt=0;attempt<100 && "PENDING".equals(afterRetry.get(1).path("readStatus").asText());attempt++) {
            sources.readPending(2);
            afterRetry=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        }
        assertEquals("FAILED",afterRetry.get(1).path("readStatus").asText());
        var v1Retry=json.createObjectNode(); v1Retry.set("sourceRef",json.valueToTree(refFrom(v1.path("ref"))));
        var denied=api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expected",ref(project),"action","RETRY_SOURCE_READ","payload",v1Retry),409);
        assertEquals("SOURCE_ALREADY_CONFIRMED",denied.path("data").path("code").asText());
        var afterV1Retry=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        assertEquals("READY",afterV1Retry.get(0).path("readStatus").asText());
    }

    @Test void freshClientReadsCurrentSourceSetHeadAndRecoversFromConflict() throws Exception {
        var project=project();
        var initial=api("GET","/projects/"+project.path("id").asText()+"/source-set/head","viewer",workspace,null,200);
        assertTrue(initial.path("ref").isNull());
        var first=upload(project,UUID.randomUUID().toString(),"first.pdf",readablePdf("first"),workspace,"member",200); sources.readPending(2);
        var confirmed=confirm(project,null,List.of(refFrom(first.path("ref"))));
        var head=api("GET","/projects/"+project.path("id").asText()+"/source-set/head","viewer",workspace,null,200);
        assertEquals(confirmed.path("ref"),head.path("ref"));

        var second=upload(project,UUID.randomUUID().toString(),"second.pdf",readablePdf("second"),workspace,"member",200); sources.readPending(2);
        var stale=confirm(project,null,List.of(refFrom(second.path("ref"))),409);
        assertEquals("SOURCE_SET_HEAD_CONFLICT",stale.path("data").path("code").asText());
        var current=api("GET","/projects/"+project.path("id").asText()+"/source-set/head","viewer",workspace,null,200);
        var recovered=confirm(project,refFrom(current.path("ref")),List.of(refFrom(second.path("ref"))));
        assertEquals(current.path("ref").path("version").asLong()+1,recovered.path("ref").path("version").asLong());
        api("GET","/projects/"+project.path("id").asText()+"/source-set/head","owner",otherWorkspace,null,404);
    }

    @Test void uploadAndConfirmationRejectLimitsAndMissingReadCompletion() throws Exception {
        var project=project();
        upload(project,UUID.randomUUID().toString(),"large.pdf",new byte[25*1024*1024+1],workspace,"member",413);
        var mismatch=mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/bidding/projects/{id}/sources",project.path("id").asText())
            .file(new MockMultipartFile("file","wrong.docx","application/pdf",readablePdf("type mismatch")))
            .param("operationId",UUID.randomUUID().toString()).param("sourceKind","TENDER")
            .header("Authorization",tokens.get("member")).header("X-Workspace-Id",workspace)).andReturn().getResponse();
        assertEquals(422,mismatch.getStatus()); assertTrue(mismatch.getContentAsString().contains("SOURCE_TYPE_MISMATCH"));
        byte[] bytes=readablePdf("Only one page"); JsonNode source=upload(project,UUID.randomUUID().toString(),"pending.pdf",bytes,workspace,"member",200);
        command(project,ref(project),"CONFIRM_SOURCE_SET",Map.of("sourceRefs",List.of(refFrom(source.path("ref"))),"exclusions",List.of()),"owner",422);
        assertEquals(1,sources.readPending(99));
    }

    @Test void failedReadCanBeRetriedWithoutChangingImmutableSourceBytes() throws Exception {
        var project=project(); byte[] corrupt=new byte[]{'%', 'P', 'D', 'F', '-', 'x'};
        var source=upload(project,UUID.randomUUID().toString(),"broken.pdf",corrupt,workspace,"member",200);
        assertEquals(1,sources.readPending(2));
        JsonNode listed=api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200);
        assertEquals("FAILED",listed.get(0).path("readStatus").asText());
        var retryPayload=json.createObjectNode(); retryPayload.set("sourceRef",json.valueToTree(refFrom(source.path("ref"))));
        String retryOperation=UUID.randomUUID().toString();
        var retry=new BiddingTypes.Command(retryOperation,ref(project),"RETRY_SOURCE_READ",retryPayload);
        api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,retry,200);
        api("POST","/projects/"+project.path("id").asText()+"/commands","member",workspace,retry,200);
        assertEquals("PENDING",api("GET","/projects/"+project.path("id").asText()+"/sources","member",workspace,null,200).get(0).path("readStatus").asText());
        assertEquals(1,sources.readPending(2));
        var response=mvc.perform(MockMvcRequestBuilders.get("/api/v1/bidding/projects/{id}/sources/{sourceId}/versions/1/content",project.path("id").asText(),source.path("id").asText())
            .header("Authorization",tokens.get("member")).header("X-Workspace-Id",workspace)).andReturn().getResponse();
        assertArrayEquals(corrupt,response.getContentAsByteArray());
    }

    @Test void fixedSourceDependenciesRequireCurrentConfirmedSet() throws Exception {
        var project=project(); byte[] bytes=readablePdf("Fixed source dependency");
        var source=upload(project,UUID.randomUUID().toString(),"fixed.pdf",bytes,workspace,"member",200); sources.readPending(2);
        var scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),project.path("id").asText());
        var sourceRef=refFrom(source.path("ref"));
        assertEquals("SOURCE_NOT_CONFIRMED",assertThrows(BiddingApiException.class,()->dependencies.validate(scope,List.of(sourceRef))).code());
        command(project,ref(project),"CONFIRM_SOURCE_SET",Map.of("sourceRefs",List.of(sourceRef),"exclusions",List.of()),"owner",200);
        assertDoesNotThrow(()->dependencies.validate(scope,List.of(sourceRef)));
    }

    @Test void readerClaimsAtMostTwoSourcesAndStartupRecoveryFailsStaleClaims() throws Exception {
        var project=project();
        for(int i=0;i<3;i++) upload(project,UUID.randomUUID().toString(),"batch-"+i+".pdf",readablePdf("Page "+i),workspace,"member",200);
        assertEquals(2,sources.readPending(20));
        var listed=api("GET","/projects/"+project.path("id").asText()+"/sources","viewer",workspace,null,200);
        assertEquals(1,java.util.stream.StreamSupport.stream(listed.spliterator(),false).filter(item->"PENDING".equals(item.path("readStatus").asText())).count());
        assertEquals(1,sources.readPending(20));
        var interrupted=upload(project,UUID.randomUUID().toString(),"interrupted.pdf",readablePdf("Pending crash"),workspace,"member",200);
        jdbc.update("UPDATE mate_bidding_source SET read_status='READING',read_token='abandoned' WHERE project_id=? AND source_id=?",project.path("id").asText(),interrupted.path("id").asText());
        assertEquals(1,sources.recoverReading());
        listed=api("GET","/projects/"+project.path("id").asText()+"/sources","viewer",workspace,null,200);
        assertTrue(java.util.stream.StreamSupport.stream(listed.spliterator(),false).anyMatch(item->
            item.path("id").asText().equals(interrupted.path("id").asText()) && "FAILED".equals(item.path("readStatus").asText()) && item.path("problems").toString().contains("READ_INTERRUPTED")));
    }

    private JsonNode upload(JsonNode project,String operationId,String filename,byte[] bytes,String ws,String role,int status) throws Exception {
        var file=new MockMultipartFile("file",filename,"application/pdf",bytes);
        var response=mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/bidding/projects/{id}/sources",project.path("id").asText())
            .file(file).param("operationId",operationId).param("sourceKind","TENDER")
            .header("Authorization",tokens.get(role)).header("X-Workspace-Id",ws)).andReturn().getResponse();
        assertEquals(status,response.getStatus(),response.getContentAsString());
        return status>=400?json.readTree(response.getContentAsString()):json.readTree(response.getContentAsString()).path("data");
    }
    private JsonNode uploadSuperseding(JsonNode project,String operationId,String filename,byte[] bytes,BiddingTypes.Ref supersedes,String ws,String role,int status) throws Exception {
        var file=new MockMultipartFile("file",filename,"application/pdf",bytes);
        var response=mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/bidding/projects/{id}/sources",project.path("id").asText())
            .file(file).param("operationId",operationId).param("sourceKind","TENDER").param("supersedesRef",json.writeValueAsString(supersedes))
            .header("Authorization",tokens.get(role)).header("X-Workspace-Id",ws)).andReturn().getResponse();
        assertEquals(status,response.getStatus(),response.getContentAsString());
        return json.readTree(response.getContentAsString()).path("data");
    }
    @Override protected JsonNode command(JsonNode project,BiddingTypes.Ref expected,String action,Map<String,?> payload,String role,int expectedStatus) throws Exception {
        if(!"CONFIRM_SOURCE_SET".equals(action)) return super.command(project,expected,action,payload,role,expectedStatus);
        var expanded=new java.util.LinkedHashMap<String,Object>(); expanded.putAll(payload); expanded.putIfAbsent("expectedSourceSetRef",null);
        return super.command(project,expected,action,expanded,role,expectedStatus);
    }
    private JsonNode confirm(JsonNode project,BiddingTypes.Ref expectedSourceSet,List<BiddingTypes.Ref> refs) throws Exception {
        return confirm(project,expectedSourceSet,refs,List.of());
    }
    private JsonNode confirm(JsonNode project,BiddingTypes.Ref expectedSourceSet,List<BiddingTypes.Ref> refs,int status) throws Exception {
        var payload=new java.util.LinkedHashMap<String,Object>(); payload.put("sourceRefs",refs); payload.put("exclusions",List.of()); payload.put("expectedSourceSetRef",expectedSourceSet);
        return super.command(project,ref(project),"CONFIRM_SOURCE_SET",payload,"owner",status);
    }
    private JsonNode confirm(JsonNode project,BiddingTypes.Ref expectedSourceSet,List<BiddingTypes.Ref> refs,List<? extends Map<String,?>> exclusions) throws Exception {
        var payload=new java.util.LinkedHashMap<String,Object>(); payload.put("sourceRefs",refs); payload.put("exclusions",exclusions); payload.put("expectedSourceSetRef",expectedSourceSet);
        return super.command(project,ref(project),"CONFIRM_SOURCE_SET",payload,"owner",200);
    }
    private BiddingTypes.Ref refFrom(JsonNode node) { return json.convertValue(node,BiddingTypes.Ref.class); }
    private byte[] readablePdf(String text) throws Exception { return pdf(List.of(text),false); }
    private byte[] pdfWithBlankPage() throws Exception { return pdf(List.of("Readable page", ""),false); }
    private byte[] scanPdf() throws Exception {
        try(var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            var page=new PDPage(); document.addPage(page);
            var image=org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,new java.awt.image.BufferedImage(6,6,java.awt.image.BufferedImage.TYPE_INT_RGB));
            try(var stream=new PDPageContentStream(document,page)) { stream.drawImage(image,40,40,100,100); }
            document.save(out); return out.toByteArray();
        }
    }
    private byte[] pdf(List<String> pages,boolean encrypted) throws Exception {
        try(var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            for(String text:pages) {
                var page=new PDPage(); document.addPage(page);
                if(!text.isEmpty()) try(var stream=new PDPageContentStream(document,page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12); stream.newLineAtOffset(50,700); stream.showText(text); stream.endText();
                }
            }
            if(encrypted) document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","user",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(out); return out.toByteArray();
        }
    }
    private String sha256(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
}

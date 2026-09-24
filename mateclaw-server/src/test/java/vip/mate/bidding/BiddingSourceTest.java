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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class BiddingSourceTest extends BiddingHttpFixture {
    @org.springframework.beans.factory.annotation.Autowired BiddingSourceService sources;
    @org.springframework.beans.factory.annotation.Autowired BiddingDependencies dependencies;

    @Test void projectSourceCapacityHasExactHundredMibBoundary() {
        assertDoesNotThrow(()->BiddingSourceService.ensureProjectCapacity(100L*1024*1024-1,1));
        var error=assertThrows(BiddingApiException.class,()->BiddingSourceService.ensureProjectCapacity(100L*1024*1024,1));
        assertEquals(413,error.status()); assertEquals("PROJECT_SOURCE_LIMIT",error.code());
    }

    @Test void uploadRejectsExistingHundredMibWithoutPersistingAnotherSource() {
        var repository=org.mockito.Mockito.mock(BiddingRepository.class);
        var access=org.mockito.Mockito.mock(BiddingAccess.class);
        var reader=org.mockito.Mockito.mock(BiddingSourceReader.class);
        var dependencies=org.mockito.Mockito.mock(BiddingDependencies.class);
        var tx=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(tx.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var jsonMapper=new com.fasterxml.jackson.databind.ObjectMapper();
        org.mockito.Mockito.when(repository.findProject("7","p")).thenReturn(jsonMapper.createObjectNode());
        org.mockito.Mockito.when(repository.lockProject("7","p")).thenReturn(true);
        org.mockito.Mockito.when(repository.sourceBytes("7","p")).thenReturn(100L*1024*1024);
        var service=new BiddingSourceService(repository,access,reader,dependencies,jsonMapper,tx,null);
        var error=assertThrows(BiddingApiException.class,()->service.upload(new BiddingTypes.Scope("7","9","p"),"op","TENDER",null,new byte[]{1},"tender.pdf"));
        assertEquals("PROJECT_SOURCE_LIMIT",error.code());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).insertSource(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
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

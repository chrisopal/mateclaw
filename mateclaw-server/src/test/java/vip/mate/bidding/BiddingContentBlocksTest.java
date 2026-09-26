package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiddingContentBlocksTest {
    private final ObjectMapper json=new ObjectMapper(); private final BiddingContentBlocks validator=new BiddingContentBlocks();

    @Test void writingCannotEmbedLocalFilesOrRawHtml() throws Exception {
        ObjectNode chapter=(ObjectNode)json.readTree("""
            {"chapterId":"c1","blocks":[{"type":"image","path":"/etc/passwd"}]}
            """);
        assertThrows(BiddingApiException.class,()->validator.validate(chapter,List.of()));
        ObjectNode html=(ObjectNode)json.readTree("""
            {"chapterId":"c1","blocks":[{"type":"paragraph","text":"<script>alert(1)</script>"}]}
            """);
        assertThrows(BiddingApiException.class,()->validator.validate(html,List.of()));
    }

    @Test void tablesMustBeRectangularAndChapterMustStayWithinTwoMiB() throws Exception {
        ObjectNode ragged=(ObjectNode)json.readTree("""
            {"chapterId":"c1","blocks":[{"type":"table","columns":["A","B"],"rows":[["1"]]}]}
            """);
        assertThrows(BiddingApiException.class,()->validator.validate(ragged,List.of()));
        ObjectNode tooLarge=json.createObjectNode().put("chapterId","c1");
        tooLarge.putArray("blocks").addObject().put("type","paragraph").put("text","x".repeat(2*1024*1024));
        BiddingApiException error=assertThrows(BiddingApiException.class,()->validator.validate(tooLarge,List.of()));
        assertEquals(413,error.status());
    }

    @Test void citationsMustMatchEvidenceAssignedToTheChapter() throws Exception {
        var validator=new BiddingSkillValidator();
        ObjectNode input=(ObjectNode)json.readTree("""
            {"requirements":[{"id":"REQ-1","evidenceRefs":[{"sourceId":"src-1","version":2,"blockId":"b-1","quote":"Exact tender text"}]}],"criteria":[],"materials":{"items":[]}}
            """);
        ObjectNode output=(ObjectNode)json.readTree("""
            {"responses":[{"requirementRef":"REQ-1","status":"RESPONDED"}],"citations":[{"requirementRef":"REQ-1","sourceId":"src-1","version":2,"blockId":"b-1","quote":"invented text"}]}
            """);
        assertThrows(BiddingApiException.class,()->validator.validateWritingEvidence(output,input));
        output.withArray("citations").set(0,json.readTree("""
            {"requirementRef":"REQ-1","sourceId":"src-1","version":2,"blockId":"b-1","quote":"Exact tender text"}
            """));
        assertDoesNotThrow(()->validator.validateWritingEvidence(output,input));
    }
}

package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Tool surface exposes only the exact fixed references in the active export claim. */
@Component
public class BiddingExportTool {
    private final BiddingEmployeeRuntime runtime;
    private final BiddingArtifactService artifacts;
    private final ObjectMapper json;
    public BiddingExportTool(BiddingEmployeeRuntime runtime,BiddingArtifactService artifacts,ObjectMapper json){this.runtime=runtime;this.artifacts=artifacts;this.json=json;}

    @Tool(name="bidding_export_document",description="Generate and verify the DOCX candidate assigned to this task.")
    public String generate(@ToolParam(description="Assigned manuscript ref JSON") String manuscriptRef,
            @ToolParam(description="Assigned template ref JSON") String templateRef,
            @ToolParam(description="Assigned format requirements ref JSON") String formatRef,ToolContext context) {
        BiddingTypes.Claim claim=runtime.claim(context);
        BiddingToolScope.require(claim,"bidding_export_document",manuscriptRef+templateRef+formatRef);
        try {
            BiddingTypes.Ref m=json.readValue(manuscriptRef,BiddingTypes.Ref.class),t=json.readValue(templateRef,BiddingTypes.Ref.class),f=json.readValue(formatRef,BiddingTypes.Ref.class);
            runtime.requireActive(claim);
            return json.writeValueAsString(artifacts.generate(claim,m,t,f,claim.input().path("mode").asText()));
        } catch(BiddingApiException e) { throw e; }
        catch(Exception e) { throw BiddingAccess.error(422,"EXPORT_INPUT_INVALID","Export references must be valid JSON refs"); }
    }
}

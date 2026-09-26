package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validates the small, inert content vocabulary accepted by chapter writers. */
public final class BiddingContentBlocks {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public void validate(ObjectNode chapter, List<BiddingTypes.Ref> allowedMaterials) {
        if (chapter == null || !chapter.isObject()) invalid("Chapter must be an object");
        only(chapter, Set.of("chapterId", "blocks"));
        if (!chapter.path("chapterId").isTextual() || chapter.path("chapterId").asText().isBlank()) invalid("chapterId is required");
        JsonNode blocks = chapter.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty()) invalid("At least one content block is required");
        Set<String> materials = new HashSet<>();
        if (allowedMaterials != null) for (BiddingTypes.Ref ref : allowedMaterials) {
            if (ref != null && "material".equals(ref.kind())) materials.add(ref.id()+":"+ref.version()+":"+ref.digest());
        }
        long bytes = chapter.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) throw BiddingAccess.error(413,"WRITING_OUTPUT_LIMIT","Chapter content exceeds 2 MiB");
        for (JsonNode block : blocks) {
            if (!block.isObject() || !block.path("type").isTextual()) invalid("Every block needs a supported type");
            switch (block.path("type").asText()) {
                case "heading" -> { only(block, Set.of("type","level","text")); integer(block.path("level"),1,6); text(block.path("text")); }
                case "paragraph" -> { only(block, Set.of("type","text")); text(block.path("text")); }
                case "list" -> {
                    only(block, Set.of("type","ordered","items"));
                    if (!block.path("ordered").isBoolean() || !block.path("items").isArray() || block.path("items").isEmpty()) invalid("List requires ordered and nonempty items");
                    for (JsonNode item : block.path("items")) text(item);
                }
                case "table" -> {
                    only(block, Set.of("type","columns","rows")); JsonNode columns=block.path("columns"), rows=block.path("rows");
                    if (!columns.isArray() || columns.isEmpty() || columns.size()>20 || !rows.isArray() || rows.isEmpty() || rows.size()>500) invalid("Table is outside supported layout limits");
                    for(JsonNode c:columns) text(c);
                    for(JsonNode row:rows) { if(!row.isArray() || row.size()!=columns.size()) invalid("Every table row must match the declared columns"); for(JsonNode cell:row) text(cell); }
                }
                case "image" -> {
                    only(block, Set.of("type","materialRef","caption","alt"));
                    JsonNode ref=block.path("materialRef");
                    if (!ref.isObject() || !"material".equals(ref.path("kind").asText())) invalid("Images must reference an authorized image material");
                    String key=ref.path("id").asText()+":"+ref.path("version").asLong(-1)+":"+ref.path("digest").asText();
                    if(!materials.contains(key)) invalid("Image material is not in the authorized input snapshot");
                    text(block.path("caption")); text(block.path("alt"));
                }
                default -> invalid("Unsupported content block type");
            }
        }
    }
    private void only(JsonNode node, Set<String> allowed) { node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) invalid("Unknown content field: "+k);}); }
    private void integer(JsonNode n,int min,int max) { if(!n.isIntegralNumber() || n.asInt()<min || n.asInt()>max) invalid("Integer is outside supported bounds"); }
    private void text(JsonNode n) { if(!n.isTextual() || n.asText().isBlank() || n.asText().length()>100_000) invalid("Text must be nonempty and bounded"); String value=n.asText(); if(value.matches("(?is).*<\\s*/?\\s*[a-z][^>]*>.*")||value.matches("(?i).*https?://.*")||value.contains("file://"))invalid("HTML and external or local URLs are not permitted"); }
    private void invalid(String message) { throw BiddingAccess.error(422,"WRITING_CONTENT_INVALID",message); }
}

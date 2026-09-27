package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.math.BigInteger;
import javax.imageio.ImageIO;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

/** Renders the bidding manuscript's inert block vocabulary to a controlled DOCX. */
@Component
public final class BiddingDocxRenderer {
    private static final String FONT = "宋体";

    public byte[] render(ObjectNode manuscript, ObjectNode template, Map<String, byte[]> authorizedImages) {
        validateTemplate(template);
        if (manuscript == null || !manuscript.path("title").isTextual() || !manuscript.path("chapters").isArray()) {
            throw invalid("Manuscript structure is invalid");
        }
        only(manuscript, java.util.Set.of("title", "chapters"));
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.getProperties().getCoreProperties().setTitle(manuscript.path("title").asText());
            configurePage(doc);
            configureStyles(doc);
            List<Heading> headings = collectHeadings(manuscript);
            addToc(doc, headings);
            int[] headingIndex = {0};
            for (JsonNode chapter : manuscript.path("chapters")) {
                if (!chapter.isObject() || !chapter.path("title").isTextual() || !chapter.path("blocks").isArray()) throw invalid("Chapter structure is invalid");
                only(chapter, java.util.Set.of("title", "level", "blocks"));
                int level = chapter.path("level").asInt(1);
                if (level < 1 || level > 3) throw invalid("Heading level is outside supported bounds");
                addHeading(doc, safeText(chapter.path("title").asText()), level, headingIndex[0]++);
                for (JsonNode block : chapter.path("blocks")) addBlock(doc, block, authorizedImages, template, headingIndex);
            }
            addPageNumber(doc);
            doc.write(out);
            return out.toByteArray();
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new BiddingApiException(422, "DOCX_RENDER_FAILED", "无法生成有效的 DOCX 候选");
        }
    }

    private void addBlock(XWPFDocument doc, JsonNode block, Map<String, byte[]> images, ObjectNode template, int[] headingIndex) throws Exception {
        if (!block.isObject() || !block.path("type").isTextual()) throw invalid("Every block needs a supported type");
        switch (block.path("type").asText()) {
            case "heading" -> {
                only(block, java.util.Set.of("type", "level", "text"));
                int level = block.path("level").asInt();
                if (level < 1 || level > 3) throw invalid("Heading level is outside supported bounds");
                addHeading(doc, requiredText(block, "text"), level, headingIndex[0]++);
            }
            case "paragraph" -> { only(block, java.util.Set.of("type", "text")); addParagraph(doc, requiredText(block, "text")); }
            case "list" -> {
                only(block, java.util.Set.of("type", "ordered", "items"));
                JsonNode items = block.path("items");
                if (!items.isArray() || items.isEmpty() || !block.path("ordered").isBoolean()) throw invalid("List structure is invalid");
                int index = 1;
                for (JsonNode item : items) {
                    String value = safeText(item.asText());
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    setRun(p.createRun(), (block.path("ordered").asBoolean() ? index++ + ". " : "• ") + value, false);
                }
            }
            case "table" -> { only(block, java.util.Set.of("type", "columns", "rows")); addTable(doc, block); }
            case "image" -> { only(block, java.util.Set.of("type", "materialRef", "caption", "alt")); addImage(doc, block, images, template); }
            default -> throw invalid("Unsupported content block type");
        }
    }

    private void addTable(XWPFDocument doc, JsonNode block) {
        JsonNode columns = block.path("columns"), rows = block.path("rows");
        if (!columns.isArray() || columns.isEmpty() || columns.size() > 20 || !rows.isArray() || rows.isEmpty() || rows.size() > 500) throw invalid("Table structure is invalid");
        XWPFTable table = doc.createTable(rows.size() + 1, columns.size());
        markHeader(table.getRow(0));
        markNoSplit(table.getRow(0));
        for (int col = 0; col < columns.size(); col++) setCell(table.getRow(0).getCell(col), safeText(columns.get(col).asText()), true);
        for (int r = 0; r < rows.size(); r++) {
            if (!rows.get(r).isArray() || rows.get(r).size() != columns.size()) throw invalid("Table row does not match columns");
            markNoSplit(table.getRow(r + 1));
            for (int c = 0; c < columns.size(); c++) setCell(table.getRow(r + 1).getCell(c), safeText(rows.get(r).get(c).asText()), false);
        }
    }

    private void addImage(XWPFDocument doc, JsonNode block, Map<String, byte[]> images, ObjectNode template) throws Exception {
        JsonNode ref = block.path("materialRef");
        if (!ref.isObject() || !"material".equals(ref.path("kind").asText())) throw imageDenied();
        String key = ref.path("id").asText() + ":" + ref.path("version").asLong(-1) + ":" + ref.path("digest").asText();
        byte[] bytes = images.get(key);
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw imageDenied();
        int type = png(bytes) ? XWPFDocument.PICTURE_TYPE_PNG : jpeg(bytes) ? XWPFDocument.PICTURE_TYPE_JPEG : 0;
        if (type == 0) throw imageDenied();
        BufferedImage image;
        try { image = ImageIO.read(new ByteArrayInputStream(bytes)); }
        catch (RuntimeException | java.io.IOException malformed) { throw imageDenied(); }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > 12000 || image.getHeight() > 12000
                || (long) image.getWidth() * image.getHeight() > 40_000_000L) throw imageDenied();
        double widthMm = Math.min(template.path("maxImageWidthMm").asDouble(), template.path("bodyWidthMm").asDouble());
        double widthPoints = widthMm * 72.0 / 25.4;
        double heightPoints = widthPoints * image.getHeight() / image.getWidth();
        XWPFParagraph p = doc.createParagraph();
        p.createRun().addPicture(new ByteArrayInputStream(bytes), type, "authorized-image", Units.toEMU(widthPoints), Units.toEMU(heightPoints));
        String caption = block.path("caption").asText("");
        if (!caption.isBlank()) addParagraph(doc, safeText(caption));
    }

    private void addHeading(XWPFDocument doc, String text, int level, int ordinal) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        String bookmark = bookmarkName(ordinal);
        BigInteger id = BigInteger.valueOf(ordinal + 1L);
        CTBookmark start = p.getCTP().addNewBookmarkStart();
        start.setId(id);
        start.setName(bookmark);
        setRun(p.createRun(), safeText(text), true);
        p.getCTP().addNewBookmarkEnd().setId(id);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        setRun(p.createRun(), safeText(text), false);
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        setRun(p.createRun(), text, bold);
    }

    private void setRun(XWPFRun run, String text, boolean bold) {
        run.setText(text);
        run.setFontFamily(FONT);
        run.setFontSize(12);
        run.setBold(bold);
    }

    private void configurePage(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr() : doc.getDocument().getBody().addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(java.math.BigInteger.valueOf(11906));
        size.setH(java.math.BigInteger.valueOf(16838));
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        java.math.BigInteger margin = java.math.BigInteger.valueOf(1417);
        mar.setTop(margin); mar.setBottom(margin); mar.setLeft(margin); mar.setRight(margin);
    }

    private void configureStyles(XWPFDocument doc) {
        XWPFStyles styles=doc.getStyles();
        if(styles==null)styles=doc.createStyles();
        for(int level=1;level<=3;level++) {
            CTStyle style=CTStyle.Factory.newInstance();
            style.setType(STStyleType.PARAGRAPH);style.setStyleId("Heading"+level);
            style.addNewName().setVal("heading "+level);style.addNewBasedOn().setVal("Normal");style.addNewNext().setVal("Normal");style.addNewQFormat();
            style.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(level-1));
            CTRPr run=style.addNewRPr();CTFonts fonts=run.addNewRFonts();fonts.setAscii(FONT);fonts.setHAnsi(FONT);fonts.setEastAsia(FONT);
            run.addNewB();run.addNewSz().setVal(java.math.BigInteger.valueOf(24));
            styles.addStyle(new XWPFStyle(style));
            CTStyle tocLevel=CTStyle.Factory.newInstance();tocLevel.setType(STStyleType.PARAGRAPH);tocLevel.setStyleId("TOC"+level);
            tocLevel.addNewName().setVal("TOC "+level);tocLevel.addNewBasedOn().setVal("Normal");
            tocLevel.addNewPPr().addNewInd().setLeft(BigInteger.valueOf((level-1)*360L));
            CTRPr tocRun=tocLevel.addNewRPr();CTFonts tocFonts=tocRun.addNewRFonts();tocFonts.setAscii(FONT);tocFonts.setHAnsi(FONT);tocFonts.setEastAsia(FONT);tocRun.addNewSz().setVal(BigInteger.valueOf(24));
            styles.addStyle(new XWPFStyle(tocLevel));
        }
        CTStyle toc=CTStyle.Factory.newInstance();toc.setType(STStyleType.PARAGRAPH);toc.setStyleId("TOCHeading");toc.addNewName().setVal("TOC Heading");toc.addNewBasedOn().setVal("Normal");
        styles.addStyle(new XWPFStyle(toc));
    }

    private void addToc(XWPFDocument doc, List<Heading> headings) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("TOCHeading");
        setRun(p.createRun(), "目录", true);
        if (headings.isEmpty()) {
            XWPFParagraph field=doc.createParagraph();field.setStyle("TOC1");
            XWPFRun begin=field.createRun();begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            XWPFRun instr=field.createRun();instr.getCTR().addNewInstrText().setStringValue(" TOC \\o \"1-3\" \\h \\z \\n ");
            XWPFRun separate=field.createRun();separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            setRun(field.createRun(), "暂无章节目录", false);
            field.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
            return;
        }
        for(int i=0;i<headings.size();i++) {
            Heading heading=headings.get(i);XWPFParagraph entry=doc.createParagraph();entry.setStyle("TOC"+heading.level());
            if(i==0) {
                entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
                entry.createRun().getCTR().addNewInstrText().setStringValue(" TOC \\o \"1-3\" \\h \\z \\n ");
                entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            }
            CTHyperlink hyperlink=entry.getCTP().addNewHyperlink();hyperlink.setAnchor(bookmarkName(i));
            XWPFHyperlinkRun link=new XWPFHyperlinkRun(hyperlink,hyperlink.addNewR(),entry);entry.addRun(link);setRun(link,heading.text(),false);
            if(i==headings.size()-1)entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
        }
    }

    private List<Heading> collectHeadings(ObjectNode manuscript) {
        List<Heading> headings=new ArrayList<>();
        for(JsonNode chapter:manuscript.path("chapters")) {
            headings.add(new Heading(safeText(chapter.path("title").asText()),chapter.path("level").asInt(1)));
            for(JsonNode block:chapter.path("blocks"))if("heading".equals(block.path("type").asText()))
                headings.add(new Heading(safeText(block.path("text").asText()),block.path("level").asInt()));
        }
        return headings;
    }

    private String bookmarkName(int ordinal) { return "biddingHeading"+String.format(java.util.Locale.ROOT,"%04d",ordinal+1); }
    private record Heading(String text,int level) {}

    private void addPageNumber(XWPFDocument doc) {
        XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
        XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph p = footer.createParagraph(); p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();
        run.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        run.getCTR().addNewInstrText().setStringValue(" PAGE ");
        run.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private void markHeader(XWPFTableRow row) { row.getCtRow().addNewTrPr().addNewTblHeader(); }
    private void markNoSplit(XWPFTableRow row) { row.getCtRow().addNewTrPr().addNewCantSplit(); }
    private void validateTemplate(ObjectNode t) {
        if (t == null || !"A4".equals(t.path("pageSize").asText()) || !FONT.equals(t.path("font").asText())
                || t.path("fontSize").asInt() != 12 || t.path("marginMm").asInt() != 25
                || t.path("bodyWidthMm").asDouble() <= 0 || t.path("bodyWidthMm").asDouble() > 160
                || t.path("maxImageWidthMm").asDouble() <= 0 || t.path("maxImageWidthMm").asDouble() > t.path("bodyWidthMm").asDouble()
                || !t.path("toc").asBoolean() || !t.path("pageNumbers").asBoolean()) throw new BiddingApiException(422, "EXPORT_FORMAT_UNSUPPORTED", "模板包含当前渲染器不支持的强制格式");
    }
    private void only(JsonNode n, java.util.Set<String> allowed) { n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid("Unsupported renderer field: "+k);}); }
    private String requiredText(JsonNode n, String field) { if (!n.path(field).isTextual()) throw invalid("Text block is invalid"); return safeText(n.path(field).asText()); }
    private String safeText(String value) { if (value == null || value.isBlank() || value.length() > 100_000 || value.matches("(?is).*<\\s*/?\\s*[a-z][^>]*>.*") || value.matches("(?i).*https?://.*") || value.contains("file://")) throw invalid("HTML and external or local URLs are not permitted"); return value; }
    private boolean png(byte[] b) { return b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47; }
    private boolean jpeg(byte[] b) { return b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff; }
    private BiddingApiException imageDenied() { return new BiddingApiException(422, "IMAGE_NOT_AUTHORIZED", "图片材料没有授权的 PNG/JPEG 字节"); }
    private BiddingApiException invalid(String message) { return new BiddingApiException(422, "WRITING_CONTENT_INVALID", message); }
}

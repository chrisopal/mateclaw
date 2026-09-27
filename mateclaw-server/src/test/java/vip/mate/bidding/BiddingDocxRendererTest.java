package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.zip.ZipInputStream;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class BiddingDocxRendererTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void generatedDocxRetainsChineseTextAndTableCells() throws Exception {
        ObjectNode book = (ObjectNode) json.readTree("""
            {"title":"技术标","chapters":[{"title":"实施范围","level":1,"blocks":[
              {"type":"paragraph","text":"一期两条产线"},
              {"type":"table","columns":["指标","要求"],"rows":[["响应时间","2秒"]]}]}]}
            """);
        byte[] bytes = new BiddingDocxRenderer().render(book, template(), Map.of());
        assertTrue(bytes.length > 1000);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("一期两条产线")));
            assertEquals("2秒", document.getTables().get(0).getRow(1).getCell(1).getText());
            assertEquals("技术标", document.getProperties().getCoreProperties().getTitle());
            var section=document.getDocument().getBody().getSectPr();
            assertTrue(section.getPgSz().xmlText().contains("11906"));
            assertEquals(java.math.BigInteger.valueOf(1417),section.getPgMar().getTop());
            assertEquals(java.math.BigInteger.valueOf(1417),section.getPgMar().getBottom());
            assertEquals(java.math.BigInteger.valueOf(1417),section.getPgMar().getLeft());
            assertEquals(java.math.BigInteger.valueOf(1417),section.getPgMar().getRight());
            var bodyRun=document.getParagraphs().stream().flatMap(p->p.getRuns().stream()).filter(r->r.getText(0)!=null&&r.getText(0).contains("一期两条产线")).findFirst().orElseThrow();
            assertTrue(bodyRun.getCTR().xmlText().contains("宋体"));
            assertTrue(bodyRun.getCTR().xmlText().contains("w:sz w:val=\"24\""));
            assertTrue(document.getDocument().getBody().xmlText().contains("TOC"));
            assertTrue(document.getFooterList().stream().flatMap(f->f.getParagraphs().stream()).flatMap(p->p.getRuns().stream()).anyMatch(r->r.getCTR().xmlText().contains("PAGE")));
        }
    }

    @Test void rendererWritesHeadingLevelsListsAndTablePaginationRules() throws Exception {
        ObjectNode book = (ObjectNode) json.readTree("""
            {"title":"结构测试","chapters":[{"title":"第一章","level":1,"blocks":[
              {"type":"heading","level":2,"text":"范围"},
              {"type":"heading","level":3,"text":"验收标准"},
              {"type":"list","ordered":true,"items":["第一项","第二项"]},
              {"type":"table","columns":["列","值"],"rows":[["行","数据"]]}]}]}
            """);
        byte[] bytes = new BiddingDocxRenderer().render(book, template(), Map.of());
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> "第一章".equals(p.getText()) && "Heading1".equals(p.getStyle())));
            assertTrue(document.getParagraphs().stream().anyMatch(p -> "范围".equals(p.getText()) && "Heading2".equals(p.getStyle())));
            assertTrue(document.getParagraphs().stream().anyMatch(p -> "验收标准".equals(p.getText()) && "Heading3".equals(p.getStyle())));
            String styles=zipEntry(bytes,"word/styles.xml");
            assertTrue(styles.contains("w:styleId=\"Heading1\"") && styles.contains("w:styleId=\"Heading2\"") && styles.contains("w:styleId=\"Heading3\""));
            assertTrue(styles.contains("w:outlineLvl w:val=\"0\"") && styles.contains("w:outlineLvl w:val=\"1\"") && styles.contains("w:outlineLvl w:val=\"2\""));
            assertTrue(styles.contains("w:eastAsia=\"宋体\"") && styles.contains("w:sz w:val=\"24\""));
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("第一项")));
            assertTrue(document.getTables().get(0).getRow(0).getCtRow().xmlText().contains("tblHeader"));
            assertTrue(document.getTables().get(0).getRow(1).getCtRow().xmlText().contains("cantSplit"));
        }
    }

    @Test void rendererRejectsUnboundImageBytesAndUnsupportedBlocks() throws Exception {
        ObjectNode image = (ObjectNode) json.readTree("""
            {"title":"图片","chapters":[{"title":"图","level":1,"blocks":[
              {"type":"image","materialRef":{"kind":"material","id":"kb:page","version":1,"digest":"sha"},"alt":"图","caption":"图1"}]}]}
            """);
        BiddingApiException unavailable = assertThrows(BiddingApiException.class,
                () -> new BiddingDocxRenderer().render(image, template(), Map.of()));
        assertEquals("IMAGE_NOT_AUTHORIZED", unavailable.code());
        ObjectNode rawHtml = (ObjectNode) json.readTree("""
            {"title":"坏内容","chapters":[{"title":"图","level":1,"blocks":[{"type":"html","text":"<script/>"}]}]}
            """);
        assertThrows(BiddingApiException.class, () -> new BiddingDocxRenderer().render(rawHtml, template(), Map.of()));
    }

    @Test void authorizedPngAndJpegAreEmbeddedAtBodyWidthWithAspectRatioAndInternalRelationships() throws Exception {
        for (String format : java.util.List.of("png", "jpeg")) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), format, encoded);
            byte[] source = encoded.toByteArray();
            String key = "kb:image:7:" + "a".repeat(64);
            ObjectNode book = (ObjectNode) json.readTree("""
                {"title":"图像","chapters":[{"title":"图","level":1,"blocks":[
                  {"type":"image","materialRef":{"kind":"material","id":"kb:image","version":7,"digest":"%s"},"alt":"示例","caption":"图1"}]}]}
                """.formatted("a".repeat(64)));
            byte[] bytes = new BiddingDocxRenderer().render(book, template(), Map.of(key, source));
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                assertEquals(1, doc.getAllPictures().size());
                var inline = doc.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                        .flatMap(r -> r.getCTR().getDrawingList().stream()).findFirst().orElseThrow().getInlineList().getFirst();
                assertEquals(org.apache.poi.util.Units.toEMU(160 / 25.4), inline.getExtent().getCx());
                assertEquals(2, (double) inline.getExtent().getCx() / inline.getExtent().getCy(), 0.02);
            }
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().endsWith(".rels")) {
                    String rels = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertFalse(rels.contains("TargetMode=\"External\""));
                }
            }
        }
    }

    @Test void rendererRejectsPseudoImageAndOversizedImageDimensions() throws Exception {
        ObjectNode book = (ObjectNode) json.readTree("""
            {"title":"图片","chapters":[{"title":"图","level":1,"blocks":[
              {"type":"image","materialRef":{"kind":"material","id":"image","version":1,"digest":"x"},"alt":"图"}]}]}
            """);
        String key = "image:1:x";
        byte[] pseudo = new byte[] {(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0,0};
        BiddingApiException fake = assertThrows(BiddingApiException.class, () -> new BiddingDocxRenderer().render(book, template(), Map.of(key, pseudo)));
        assertEquals("IMAGE_NOT_AUTHORIZED", fake.code());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12001, 1, BufferedImage.TYPE_INT_RGB), "png", encoded);
        BiddingApiException large = assertThrows(BiddingApiException.class, () -> new BiddingDocxRenderer().render(book, template(), Map.of(key, encoded.toByteArray())));
        assertEquals("IMAGE_NOT_AUTHORIZED", large.code());
    }

    @Test void rendererRejectsUnsupportedTemplateGeometry() throws Exception {
        ObjectNode book = (ObjectNode) json.readTree("{\"title\":\"t\",\"chapters\":[]}");
        ObjectNode invalid = template().put("marginMm", 40);
        BiddingApiException rejected = assertThrows(BiddingApiException.class,
                () -> new BiddingDocxRenderer().render(book, invalid, Map.of()));
        assertEquals("EXPORT_FORMAT_UNSUPPORTED", rejected.code());
    }

    private ObjectNode template() throws Exception {
        return (ObjectNode) json.readTree("""
            {"version":"1","pageSize":"A4","font":"宋体","fontSize":12,"marginMm":25,
             "bodyWidthMm":160,"maxImageWidthMm":160,"toc":true,"pageNumbers":true,
             "headingStyles":{"1":"Heading1","2":"Heading2","3":"Heading3"}}
            """);
    }

    private String zipEntry(byte[] bytes,String name) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(name.equals(entry.getName()))return new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }
}

package vip.mate.presales;

import org.junit.jupiter.api.Test;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PresalesArtifactRendererTest {
    @Test void exportsEditableChineseOfficeWithDraftMarksAndSplitLongContent() throws Exception {
        var renderer = new PresalesArtifactRenderer();
        String content = "一期仅两条产线，预算未知，不承诺预测维护能力。".repeat(80);
        var files = renderer.render(new PresalesArtifactRenderer.Document("制造业方案", "v1", true,
                List.of(new PresalesArtifactRenderer.Section("范围与假设", content)), "来源仅供内部核对"));
        assertEquals(3, files.size());
        assertTrue(new String(files.get("solution.md"), java.nio.charset.StandardCharsets.UTF_8).contains("未批准草稿"));
        try (var word = new XWPFDocument(new ByteArrayInputStream(files.get("solution.docx")))) {
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().equals(content)));
            assertEquals("Heading1", word.getParagraphs().getFirst().getStyle());
            assertTrue(word.getFooterList().getFirst().getText().contains("未批准草稿"));
        }
        try (var ppt = new XMLSlideShow(new ByteArrayInputStream(files.get("solution.pptx")))) {
            assertTrue(ppt.getSlides().size() > 3);
            for (var slide : ppt.getSlides()) {
                var shapes = slide.getShapes().stream().filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast).toList();
                assertTrue(shapes.stream().anyMatch(s -> s.getText().contains("未批准草稿")));
                assertTrue(shapes.stream().allMatch(s -> s.getAnchor().getMaxY() <= 540));
            }
        }
        assertEquals(64, PresalesArtifactRenderer.digest(files.get("solution.docx")).length());
    }
    @Test void splittingPreservesUnicodeCodePoints() {
        assertEquals(List.of("设备😀", "完成"), PresalesArtifactRenderer.wrap("设备😀完成", 3));
    }
}

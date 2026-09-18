package vip.mate.presales;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

/** Fixed, editable Office template. No URLs, executable markup, or external assets are resolved. */
@Component
public class PresalesArtifactRenderer {
    public static final String TEMPLATE_VERSION = "presales-enterprise-1";
    public record Section(String title, String text) {}
    public record Document(String title, String revision, boolean provisional, List<Section> sections, String appendix) {}

    public Map<String, byte[]> render(Document document) {
        Objects.requireNonNull(document);
        if (document.sections() == null || document.sections().size() > 100)
            throw new IllegalArgumentException("At most 100 sections required");
        if (document.sections().stream().anyMatch(s -> s == null || s.text() == null || s.text().length() > 100_000))
            throw new IllegalArgumentException("Invalid section");
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("solution.md", markdown(document).getBytes(StandardCharsets.UTF_8));
            files.put("solution.docx", word(document));
            files.put("solution.pptx", slides(document));
            return Collections.unmodifiableMap(files);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Artifact rendering failed", e);
        }
    }

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String safe(String value) {
        return Objects.toString(value, "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    }
    private static String label(Document d) {
        return (d.provisional() ? "未批准草稿 · 需求与发布待确认 | " : "") + "版本 " + safe(d.revision());
    }
    private static String markdown(Document d) {
        StringBuilder out = new StringBuilder("# ").append(safe(d.title())).append("\n\n").append(label(d)).append("\n\n");
        for (Section s : d.sections()) out.append("## ").append(safe(s.title())).append("\n\n").append(safe(s.text())).append("\n\n");
        if (d.appendix() != null && !d.appendix().isBlank()) out.append("## 附录\n\n").append(safe(d.appendix()));
        return out.toString();
    }
    private static byte[] word(Document d) throws java.io.IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var styles = doc.createStyles();
            // Explicit paragraph styles preserve editable document heading structure.
            for (int level = 1; level <= 2; level++) {
                var style = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();
                style.setStyleId("Heading" + level);
                style.addNewName().setVal("heading " + level);
                style.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(level - 1));
                styles.addStyle(new XWPFStyle(style));
            }
            paragraph(doc, safe(d.title()), "Heading1", 22);
            paragraph(doc, label(d), null, 10);
            var footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            footer.createParagraph().createRun().setText(label(d));
            for (Section s : d.sections()) {
                paragraph(doc, safe(s.title()), "Heading2", 15);
                for (String line : safe(s.text()).split("\\R", -1)) paragraph(doc, line, null, 11);
            }
            if (d.appendix() != null && !d.appendix().isBlank()) {
                paragraph(doc, "附录", "Heading2", 15);
                for (String line : safe(d.appendix()).split("\\R")) paragraph(doc, line, null, 10);
            }
            doc.write(out); return out.toByteArray();
        }
    }
    private static void paragraph(XWPFDocument doc, String text, String style, int size) {
        var p = doc.createParagraph();
        if (style != null) { p.setStyle(style); p.setKeepNext(true); }
        p.setSpacingAfter(140);
        var run = p.createRun(); run.setFontFamily("Microsoft YaHei"); run.setFontSize(size); run.setText(text);
    }
    private static byte[] slides(Document d) throws java.io.IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(960, 540));
            addSlide(ppt, safe(d.title()), label(d), label(d));
            for (Section s : d.sections()) {
                List<String> lines = wrap(safe(s.text()), 44);
                for (int i = 0; i < Math.max(1, lines.size()); i += 11) {
                    String title = safe(s.title()) + (i > 0 ? "（续）" : "");
                    String body = String.join("\n", lines.subList(i, Math.min(i + 11, lines.size())));
                    addSlide(ppt, title, body, label(d));
                    if (ppt.getSlides().size() > 250) throw new IllegalArgumentException("Output exceeds 250 slides");
                }
            }
            if (d.appendix() != null && !d.appendix().isBlank()) {
                List<String> lines = wrap(safe(d.appendix()), 44);
                for (int i = 0; i < lines.size(); i += 11)
                    addSlide(ppt, "附录", String.join("\n", lines.subList(i, Math.min(i + 11, lines.size()))), label(d));
            }
            ppt.write(out); return out.toByteArray();
        }
    }
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            int[] points = paragraph.codePoints().toArray();
            if (points.length == 0) lines.add("");
            for (int i = 0; i < points.length; i += width)
                lines.add(new String(points, i, Math.min(width, points.length - i)));
        }
        return lines;
    }
    private static void addSlide(XMLSlideShow ppt, String title, String body, String footer) {
        var slide = ppt.createSlide();
        text(slide, title, 40, 28, 880, 70, 26, true, "0B1220");
        text(slide, body, 40, 112, 880, 352, 19, false, "26354A");
        text(slide, footer + " · " + ppt.getSlides().size(), 40, 493, 880, 25, 10, false, "526075");
    }
    private static void text(XSLFSlide slide, String value, double x, double y, double w, double h, double size, boolean bold, String color) {
        var box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.setWordWrap(true); box.clearText();
        for (String line : value.split("\\n", -1)) {
            var p = box.addNewTextParagraph(); p.setSpaceAfter(3d);
            var run = p.addNewTextRun(); run.setText(line); run.setFontFamily("Microsoft YaHei");
            run.setFontSize(size); run.setBold(bold); run.setFontColor(Color.decode("#" + color));
        }
    }
}

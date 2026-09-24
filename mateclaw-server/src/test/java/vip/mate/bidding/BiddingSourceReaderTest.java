package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class BiddingSourceReaderTest {
    private final BiddingSourceReader reader = new BiddingSourceReader();

    @Test void docxKeepsTableCellLocatorAndValue() throws Exception {
        byte[] bytes;
        try (var d = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            d.createParagraph().createRun().setText("评分标准");
            d.createTable(1, 2).getRow(0).getCell(1).setText("12.50 分");
            d.write(out); bytes = out.toByteArray();
        }
        var extraction = reader.read(bytes, "tender.docx");
        assertTrue(extraction.complete(), extraction.problems().toString());
        var expected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/bidding/reader/expected.json"));
        assertEquals(expected.path("completeForSimpleDocx").asBoolean(),extraction.complete());
        assertTrue(extraction.blocks().stream().anyMatch(b -> b.locator().equals(expected.path("docxCellLocator").asText()) && b.text().equals(expected.path("docxCellValue").asText())));
        assertEquals(extraction.blocks().stream().map(BiddingTypes.ReadBlock::id).toList(),
            reader.read(bytes, "tender.docx").blocks().stream().map(BiddingTypes.ReadBlock::id).toList());
    }

    @Test void pdfUsesActualPageNumbersAndFlagsImageOnlyPage() throws Exception {
        byte[] bytes = pdf(List.of("First page amount 12.50", "", "Third page amount 7"), false);
        var extraction = reader.read(bytes, "tender.pdf");
        assertFalse(extraction.complete());
        assertTrue(extraction.blocks().stream().anyMatch(b -> Integer.valueOf(1).equals(b.pdfPage()) && b.text().contains("12.50")));
        assertTrue(extraction.blocks().stream().filter(b -> Integer.valueOf(1).equals(b.pdfPage())).allMatch(b -> b.locator().contains("bbox:") && b.locator().contains("order:")));
        assertTrue(extraction.problems().stream().anyMatch(p -> p.contains("EMPTY_PDF_PAGE:2")));
        assertTrue(extraction.blocks().stream().anyMatch(b -> Integer.valueOf(2).equals(b.pdfPage()) && b.kind().equals("EMPTY_PAGE")));
        assertEquals(List.of(1,3),extraction.blocks().stream().filter(b -> "PDF_TEXT".equals(b.kind())).map(BiddingTypes.ReadBlock::pdfPage).toList());
        assertEquals(extraction.blocks().stream().map(BiddingTypes.ReadBlock::id).toList(),reader.read(bytes,"tender.pdf").blocks().stream().map(BiddingTypes.ReadBlock::id).toList());
    }

    @Test void rejectsPdfAboveFiveHundredPagesBeforeExtraction() throws Exception {
        byte[] bytes;
        try(var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            for(int i=0;i<501;i++) document.addPage(new PDPage()); document.save(out); bytes=out.toByteArray();
        }
        assertEquals("SOURCE_PAGE_LIMIT",assertThrows(BiddingApiException.class,()->reader.read(bytes,"too-many-pages.pdf")).code());
    }

    @Test void imageOnlyScannedPageIsSubstantiveAndCannotBeExcludedAsBlank() throws Exception {
        try(var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            var page=new PDPage(); document.addPage(page);
            var image=LosslessFactory.createFromImage(document,new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB));
            try(var stream=new PDPageContentStream(document,page)) { stream.drawImage(image,40,40,100,100); }
            document.save(out);
            var extraction=reader.read(out.toByteArray(),"scan.pdf");
            assertFalse(extraction.complete()); assertTrue(extraction.problems().contains("IMAGE_ONLY_PDF_PAGE:1"));
            assertTrue(extraction.blocks().stream().anyMatch(b -> b.kind().equals("IMAGE")));
        }
    }

    @Test void mixedTextAndScannedImagePageRequiresReview() throws Exception {
        try(var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            var page=new PDPage(); document.addPage(page);
            try(var stream=new PDPageContentStream(document,page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12); stream.newLineAtOffset(40,700); stream.showText("Visible text"); stream.endText();
                var image=org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB));
                stream.drawImage(image,40,40,100,100);
            }
            document.save(out);
            var extraction=reader.read(out.toByteArray(),"mixed.pdf");
            assertFalse(extraction.complete()); assertTrue(extraction.problems().contains("PDF_IMAGE_CONTENT_REQUIRES_REVIEW:1"));
            assertTrue(extraction.blocks().stream().anyMatch(b -> "IMAGE".equals(b.kind()) && b.locator().contains("page:1")));
        }
    }

    @Test void rejectsDamagedEncryptedAndMismatchedFiles() throws Exception {
        assertThrows(BiddingApiException.class, () -> reader.read(new byte[]{1,2,3}, "bad.pdf"));
        assertThrows(BiddingApiException.class, () -> reader.read(pdf(List.of("secret"), true), "locked.pdf"));
        assertThrows(BiddingApiException.class, () -> reader.read(pdf(List.of("valid"), false), "valid.docx"));
    }

    @Test void rejectsFileAndExtractedTextLimitsWithoutTruncation() throws Exception {
        assertEquals("SOURCE_FILE_LIMIT",assertThrows(BiddingApiException.class,
            () -> reader.read(new byte[25*1024*1024+1],"large.pdf")).code());
        byte[] bytes;
        try(var d=new XWPFDocument(); var out=new ByteArrayOutputStream()) {
            var random=new java.util.Random(17); var text=new StringBuilder(1_000_001);
            for(int i=0;i<1_000_001;i++) text.append((char)('a'+random.nextInt(26)));
            d.createParagraph().createRun().setText(text.toString()); d.write(out); bytes=out.toByteArray();
        }
        assertEquals("SOURCE_TEXT_LIMIT",assertThrows(BiddingApiException.class,()->reader.read(bytes,"long.docx")).code());
    }

    private byte[] pdf(List<String> pages, boolean encrypt) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage(); doc.addPage(page);
                if (!text.isEmpty()) try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText(text); stream.endText();
                }
            }
            if (encrypt) doc.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            doc.save(out); return out.toByteArray();
        }
    }
}

package vip.mate.bidding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

/** Extracts complete, reviewable text blocks without silently truncating source material. */
@Component
public class BiddingSourceReader {
    private static final int MAX_TEXT_CODE_POINTS = 1_000_000;

    public BiddingTypes.Extraction read(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) throw invalid("SOURCE_EMPTY", "Source file is empty");
        if (bytes.length > 25 * 1024 * 1024) throw new BiddingApiException(413, "SOURCE_FILE_LIMIT", "文件超过25MiB上限");
        String suffix = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            String digest = sha256(bytes);
            if (isZip(bytes)) {
                if (!suffix.endsWith(".docx")) throw invalid("SOURCE_TYPE_MISMATCH", "DOCX content must use a .docx filename");
                return readDocx(bytes, digest);
            }
            if (isPdf(bytes)) {
                if (!suffix.endsWith(".pdf")) throw invalid("SOURCE_TYPE_MISMATCH", "PDF content must use a .pdf filename");
                return readPdf(bytes, digest);
            }
            throw invalid("SOURCE_TYPE_UNSUPPORTED", "Only DOCX and PDF files are supported");
        } catch (BiddingApiException e) { throw e; }
        catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw invalid("SOURCE_ENCRYPTED", "Encrypted PDF cannot be read without a password");
        } catch (Exception e) {
            throw invalid("SOURCE_CORRUPT", "Source file could not be read");
        }
    }

    private BiddingTypes.Extraction readDocx(byte[] bytes, String digest) throws Exception {
        List<BiddingTypes.ReadBlock> blocks = new ArrayList<>(); List<String> problems = new ArrayList<>();
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            readBody(document.getBodyElements(),"body",digest,blocks,problems);
            for(int i=0;i<document.getHeaderList().size();i++) readBody(document.getHeaderList().get(i).getBodyElements(),"header:"+i,digest,blocks,problems);
            for(int i=0;i<document.getFooterList().size();i++) readBody(document.getFooterList().get(i).getBodyElements(),"footer:"+i,digest,blocks,problems);
            for(var part:document.getPackage().getParts()) {
                String name=part.getPartName().getName();
                if(!name.matches("/word/(document|header[0-9]*|footer[0-9]*|footnotes|endnotes)\\.xml")) continue;
                String xml;
                try(var input=part.getInputStream()) { xml=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
                var textbox=java.util.regex.Pattern.compile("<(?:[A-Za-z0-9]+:)?txbxContent\\b").matcher(xml);
                int number=0;
                while(textbox.find()) {
                    String locator="package"+name+"/textbox:"+(number++);
                    addSpecial(blocks,digest,null,locator,"TEXT_BOX","NEEDS_REVIEW");
                    problems.add("TEXT_BOX_REQUIRES_REVIEW:"+locator);
                }
            }
            if (!document.getAllPictures().isEmpty()) problems.add("EMBEDDED_IMAGE_REQUIRES_REVIEW");
            int imageNo=0;
            for(var picture:document.getAllPictures()) addSpecial(blocks,digest,null,"package/image:"+(imageNo++),"IMAGE","NEEDS_REVIEW");
            for (var relationship : document.getPackage().getRelationships())
                if (relationship.getTargetMode() == org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL) problems.add("EXTERNAL_LINK_REQUIRES_REVIEW");
            for (var part : document.getPackage().getParts())
                if (part.getContentType().toLowerCase(Locale.ROOT).contains("oleobject")) {
                    problems.add("OLE_OBJECT_REQUIRES_REVIEW"); addSpecial(blocks,digest,null,"package/ole:"+part.getPartName(),"OLE","NEEDS_REVIEW");
                }
            if(blocks.isEmpty()) problems.add("NO_READABLE_CONTENT");
        }
        validateTotal(blocks);
        boolean complete = problems.isEmpty();
        return new BiddingTypes.Extraction(List.copyOf(blocks), complete, List.copyOf(problems));
    }

    private static void readBody(List<IBodyElement> elements,String prefix,String digest,
        List<BiddingTypes.ReadBlock> blocks,List<String> problems) {
            int para = 0, table = 0;
            for (IBodyElement element : elements) {
                if (element instanceof XWPFParagraph paragraph) {
                    add(blocks, digest, null, prefix+"/paragraph:" + para++, paragraph.getText(), "TEXT", "READABLE");
                } else if (element instanceof XWPFTable xwpfTable) {
                    int tableNo = table++;
                    for (int rowNo = 0; rowNo < xwpfTable.getRows().size(); rowNo++) {
                        var row = xwpfTable.getRow(rowNo);
                        if (row.getTableCells().size() != row.getCtRow().getTcList().size()) {
                            problems.add("UNRESOLVED_TABLE_CELLS:" + tableNo + ":" + rowNo); continue;
                        }
                        for (int cellNo = 0; cellNo < row.getTableCells().size(); cellNo++) {
                            XWPFTableCell cell = row.getCell(cellNo);
                            if (cell.getCTTc().getTcPr() != null && (cell.getCTTc().getTcPr().getGridSpan() != null || cell.getCTTc().getTcPr().getVMerge() != null)) {
                                problems.add("MERGED_TABLE_CELL:" + tableNo + ":" + rowNo + ":" + cellNo);
                            }
                            if(cell.getBodyElements().stream().anyMatch(body -> body instanceof XWPFTable))
                                problems.add("NESTED_TABLE_REQUIRES_REVIEW:"+tableNo+":"+rowNo+":"+cellNo);
                            add(blocks, digest, null, prefix+"/table:" + tableNo + "/row:" + rowNo + "/cell:" + cellNo,
                                cell.getText(), "TABLE_CELL", "READABLE");
                        }
                    }
                }
            }
    }

    private BiddingTypes.Extraction readPdf(byte[] bytes, String digest) throws Exception {
        List<BiddingTypes.ReadBlock> blocks = new ArrayList<>(); List<String> problems = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw invalid("SOURCE_ENCRYPTED", "Encrypted PDF cannot be read without a password");
            int pages = document.getNumberOfPages();
            if (pages > 500) throw new BiddingApiException(413, "SOURCE_PAGE_LIMIT", "PDF超过500页上限");
            for (int page = 1; page <= pages; page++) {
                final int pageNo = page;
                final AtomicInteger order = new AtomicInteger();
                PositionedStripper stripper = new PositionedStripper((text, positions) -> {
                    if (!text.isBlank()) {
                        TextPosition first = positions.getFirst(), last = positions.getLast();
                        String locator = String.format(Locale.ROOT, "page:%d/bbox:%.2f,%.2f,%.2f,%.2f/order:%d", pageNo,
                            first.getXDirAdj(), first.getYDirAdj(), Math.max(0, last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj()), first.getHeightDir(), order.getAndIncrement());
                        add(blocks, digest, pageNo, locator, text, "PDF_TEXT", "READABLE");
                    }
                });
                stripper.setSortByPosition(true); stripper.setStartPage(page); stripper.setEndPage(page); stripper.getText(document);
                boolean hasText=blocks.stream().anyMatch(b -> b.pdfPage() != null && b.pdfPage() == pageNo);
                boolean hasImage=false;
                var resources=document.getPage(page-1).getResources();
                if(resources!=null) for(var name:resources.getXObjectNames()) if(resources.getXObject(name) instanceof PDImageXObject) hasImage=true;
                if(hasImage) {
                    problems.add((hasText?"PDF_IMAGE_CONTENT_REQUIRES_REVIEW:":"IMAGE_ONLY_PDF_PAGE:")+page);
                    addSpecial(blocks,digest,page,"page:"+page+"/image:0","IMAGE","NEEDS_REVIEW");
                } else if(!hasText) {
                    boolean visible=hasVisiblePageContent(document,page-1);
                    if(visible) {
                        problems.add("UNREADABLE_PDF_PAGE:"+page);
                        addSpecial(blocks,digest,page,"page:"+page+"/unreadable","UNREADABLE_PAGE","NEEDS_REVIEW");
                    } else {
                        problems.add("EMPTY_PDF_PAGE:" + page);
                        addSpecial(blocks,digest,page,"page:"+page+"/empty","EMPTY_PAGE","NEEDS_REVIEW");
                    }
                }
            }
            if(pages==0) problems.add("NO_PDF_PAGES");
        }
        validateTotal(blocks);
        return new BiddingTypes.Extraction(List.copyOf(blocks), problems.isEmpty(), List.copyOf(problems));
    }

    private static final class PositionedStripper extends PDFTextStripper {
        private final LineConsumer consumer;
        PositionedStripper(LineConsumer consumer) throws IOException { this.consumer = consumer; }
        @Override protected void writeString(String text, List<TextPosition> positions) { consumer.accept(text, positions); }
    }
    @FunctionalInterface private interface LineConsumer { void accept(String text, List<TextPosition> positions); }

    private static void add(List<BiddingTypes.ReadBlock> blocks, String digest, Integer page, String locator,
                            String text, String kind, String quality) {
        if (text == null || text.isBlank()) return;
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) throw new BiddingApiException(413, "SOURCE_TEXT_LIMIT", "文件内容超出读取上限");
        String id = sha256((digest + "\n" + locator + "\n" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        blocks.add(new BiddingTypes.ReadBlock(id, page, locator, text, kind, quality));
    }
    private static void addSpecial(List<BiddingTypes.ReadBlock> blocks,String digest,Integer page,String locator,String kind,String quality) {
        String id=sha256((digest+"\n"+locator+"\n"+kind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        blocks.add(new BiddingTypes.ReadBlock(id,page,locator,"",kind,quality));
    }
    private static void validateTotal(List<BiddingTypes.ReadBlock> blocks) {
        long points=0;
        for(var block:blocks) { points+=block.text().codePointCount(0,block.text().length()); if(points>MAX_TEXT_CODE_POINTS) throw new BiddingApiException(413,"SOURCE_TEXT_LIMIT","文件内容超出读取上限"); }
    }
    private static boolean hasVisiblePageContent(PDDocument document,int pageIndex) throws IOException {
        var image=new PDFRenderer(document).renderImageWithDPI(pageIndex,24);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int color=image.getRGB(x,y); int r=(color>>>16)&255,g=(color>>>8)&255,b=color&255;
            if(r<245 || g<245 || b<245) return true;
        }
        return false;
    }
    private static boolean isPdf(byte[] b) { return b.length >= 5 && b[0]=='%' && b[1]=='P' && b[2]=='D' && b[3]=='F' && b[4]=='-'; }
    private static boolean isZip(byte[] b) { return b.length >= 4 && b[0]=='P' && b[1]=='K' && (b[2]==3 || b[2]==5 || b[2]==7); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static BiddingApiException invalid(String code, String message) { return new BiddingApiException(422, code, message); }
}

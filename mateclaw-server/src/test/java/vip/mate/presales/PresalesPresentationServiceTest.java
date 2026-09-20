package vip.mate.presales;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PresalesPresentationServiceTest {
  private static final String SVG="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\"><text x=\"64\" y=\"680\">未批准草稿</text></svg>";
  @Test void acceptsEditableSelfContainedPage(){assertDoesNotThrow(()->PresalesPresentationService.validateSvg(SVG));}
  @Test void rejectsScriptExternalFilesAndEntities(){
    for(String addition:new String[]{"<script>alert(1)</script>","<image href=\"file:///etc/passwd\"/>","<foreignObject/>","<rect fill=\"url(https://evil.test/x)\"/>","<text onclick=\"alert(1)\">x</text>"})
      assertThrows(RuntimeException.class,()->PresalesPresentationService.validateSvg(SVG.replace("</svg>",addition+"</svg>")));
    assertThrows(RuntimeException.class,()->PresalesPresentationService.validateSvg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+SVG));
  }
  @Test void rejectsUnmarkedOrWrongCanvas(){
    assertThrows(RuntimeException.class,()->PresalesPresentationService.validateSvg(SVG.replace("未批准草稿","正式成果")));
    assertThrows(RuntimeException.class,()->PresalesPresentationService.validateSvg(SVG.replace("1280 720","800 600")));
  }
}

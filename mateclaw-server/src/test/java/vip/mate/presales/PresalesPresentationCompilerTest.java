package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/** Optional real installed-skill smoke test; no model or network calls. */
class PresalesPresentationCompilerTest {
 @Test @EnabledIfEnvironmentVariable(named="PRESALES_PPT_SKILL_ROOT",matches=".+")
 void compilesEditableSlidesWithInstalledSkillAndPersistsExactBytes() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:pptcompiler;DB_CLOSE_DELAY=-1","sa","");
  var jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE mate_presales_artifact(project_id VARCHAR,release_id VARCHAR,filename VARCHAR,digest VARCHAR,content_base64 CLOB,PRIMARY KEY(project_id,release_id,filename))");
  var json=new ObjectMapper();
  var service=new PresalesPresentationService(jdbc,json,mock(vip.mate.skill.runtime.SkillRuntimeService.class),mock(vip.mate.agent.binding.service.AgentBindingService.class),new DataSourceTransactionManager(ds));
  ReflectionTestUtils.setField(service,"trustedRoot",System.getenv("PRESALES_PPT_SKILL_ROOT"));
  ReflectionTestUtils.setField(service,"python",System.getenv("PRESALES_PPT_PYTHON"));
  var result=json.createObjectNode();var solution=result.putObject("solution").put("title","Compiler fixture");
  solution.putObject("presentation").putArray("slides").addObject().put("title","范围").put("svg","""
   <svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
    <rect x="0" y="0" width="1280" height="720" fill="#FFFFFF"/>
    <rect x="64" y="64" width="8" height="48" fill="#0966D9"/>
    <text x="96" y="101" font-size="36" font-family="Arial" fill="#14243A">范围与待确认事项</text>
    <text x="64" y="200" font-size="26" font-family="Arial" fill="#14243A">两条产线试点；预算与日期待客户确认。</text>
    <text x="64" y="672" font-size="18" font-family="Arial" fill="#526075">未批准草稿</text>
   </svg>
   """);
  service.prepare(result,"1","p","run");
  assertEquals("ppt-master-plus",solution.path("presentation").path("skill").asText());
  assertFalse(solution.path("presentation").path("slides").get(0).has("svg"));
  var bytes=Base64.getDecoder().decode(jdbc.queryForObject("SELECT content_base64 FROM mate_presales_artifact WHERE filename='solution.pptx'",String.class));
  assertEquals(PresalesArtifactRenderer.digest(bytes),solution.path("presentation").path("sha256").asText());
  try(var deck=new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(bytes))){
   assertEquals(1,deck.getSlides().size());
   assertTrue(deck.getSlides().get(0).getShapes().stream().anyMatch(s->s instanceof org.apache.poi.xslf.usermodel.XSLFTextShape));
  }
 }
}

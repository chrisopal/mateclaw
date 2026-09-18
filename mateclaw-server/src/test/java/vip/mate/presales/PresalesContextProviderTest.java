package vip.mate.presales;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.semantic.web.SemanticApiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PresalesContextProviderTest {
 @Test void projectSourceScopeAndUpdatesAreEnforced() throws Exception {
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:presalesctx;DB_CLOSE_DELAY=-1","sa",""));
  jdbc.execute("CREATE TABLE mate_semantic_source_governance(graph_id VARCHAR,source_id VARCHAR,state VARCHAR)");
  jdbc.execute("CREATE TABLE mate_wiki_knowledge_base(id BIGINT,workspace_id BIGINT,deleted INT)");
  jdbc.execute("CREATE TABLE mate_wiki_raw_material(id BIGINT,kb_id BIGINT,title VARCHAR,extracted_text VARCHAR,original_content VARCHAR,deleted INT)");
  jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(1,10,0),(2,10,0),(3,20,0)");
  jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(11,1,'A','一期两条线',NULL,0),(22,2,'B','另一个项目资料',NULL,0)");
  var access=mock(PresalesAccess.class);when(access.require("10","member")).thenReturn("7");var json=new ObjectMapper();
  var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"id\":\"p1\",\"version\":1,\"materials\":[{\"kbId\":\"1\",\"graphId\":\"g1\",\"role\":\"PROJECT\"}],\"reviews\":[{\"summary\":\"self-approved\"}]}");
  var provider=new PresalesContextProvider(jdbc,json,access);var snapshot=provider.snapshot("10",p,"S1","理解项目");
  assertEquals(1,snapshot.path("sources").size());assertEquals("11",snapshot.path("sources").get(0).path("sourceRef").asText());assertFalse(snapshot.has("reviews"));
  provider.revalidate("10",p,snapshot);
  jdbc.update("UPDATE mate_wiki_raw_material SET extracted_text='改为六条线' WHERE id=11");
  assertEquals("SOURCE_CHANGED",assertThrows(SemanticApiException.class,()->provider.revalidate("10",p,snapshot)).code());
  jdbc.update("INSERT INTO mate_semantic_source_governance VALUES('g1','11','WITHDRAWN')");
  assertEquals("SOURCE_WITHDRAWN",assertThrows(SemanticApiException.class,()->provider.snapshot("10",p,"S1","理解")).code());
 }
}

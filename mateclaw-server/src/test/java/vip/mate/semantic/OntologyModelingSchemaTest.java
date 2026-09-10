package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** H2 compatibility smoke tests, not native database certification. */
class OntologyModelingSchemaTest {
    @ParameterizedTest @ValueSource(strings={"h2","mysql","kingbase"})
    void persistsTasksAndEnforcesScopedOperationUniqueness(String dialect)throws Exception {
        String mode=dialect.equals("mysql")?";MODE=MySQL":dialect.equals("kingbase")?";MODE=PostgreSQL":"";
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:modeling-"+UUID.randomUUID()+mode)) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+dialect+"/V208__semantic_modeling_tasks.sql"));
            String insert="INSERT INTO mate_semantic_modeling_task(id,workspace_id,operation_id,request_digest,ontology_id,state_json,created_at,updated_at) VALUES('%s',%d,'op','digest','ontology','{\"stage\":\"READY\"}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
            try(var statement=connection.createStatement()) {
                statement.execute(insert.formatted("one",1));
                statement.execute(insert.formatted("other-workspace",2));
                assertThrows(SQLException.class,()->statement.execute(insert.formatted("duplicate",1)));
                statement.execute("INSERT INTO mate_semantic_modeling_operation(task_id,operation_id,request_digest,result_json) VALUES('one','accept','digest','{}')");
                assertThrows(SQLException.class,()->statement.execute("INSERT INTO mate_semantic_modeling_operation(task_id,operation_id,request_digest,result_json) VALUES('one','accept','digest','{}')"));
                try(var rows=statement.executeQuery("SELECT state_json FROM mate_semantic_modeling_task WHERE id='one'")){assertTrue(rows.next());assertTrue(rows.getString(1).contains("READY"));}
            }
        }
    }
}

package vip.mate.workspace.core.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

class WorkspaceAccessServiceTest {
    private JdbcTemplate jdbc;
    private WorkspaceAccessService access;

    @BeforeEach
    void setup() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:workspace_access_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY,name VARCHAR(100),slug VARCHAR(100),description VARCHAR(100),owner_id BIGINT,base_path VARCHAR(100),settings_json VARCHAR(100),create_time TIMESTAMP,update_time TIMESTAMP,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id BIGINT PRIMARY KEY,workspace_id BIGINT,user_id BIGINT,role VARCHAR(20),create_time TIMESTAMP,update_time TIMESTAMP,deleted INT)");
        jdbc.update("INSERT INTO mate_workspace(id,owner_id,deleted) VALUES(1,9,0),(2,10,0)");
        jdbc.update(
                "INSERT INTO mate_workspace_member(id,workspace_id,user_id,role,deleted) VALUES(19,1,9,'member',0),(20,2,10,'admin',0)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WorkspaceMapper.class);
        configuration.addMapper(WorkspaceMemberMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var sessions = new SqlSessionTemplate(factory.getObject());
        access =
                new WorkspaceAccessService(
                        sessions.getMapper(WorkspaceMapper.class),
                        sessions.getMapper(WorkspaceMemberMapper.class));
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void uncachedMembershipImmediatelyReflectsRoleAndDeletion() {
        assertTrue(access.hasMinimumRole(1, 9, "member"));
        jdbc.update("UPDATE mate_workspace_member SET role='viewer' WHERE id=19");
        assertFalse(access.hasMinimumRole(1, 9, "member"));
        jdbc.update("UPDATE mate_workspace_member SET deleted=1 WHERE id=19");
        assertNull(access.findActiveMembership(1, 9));
        jdbc.update("UPDATE mate_workspace_member SET deleted=NULL WHERE id=19");
        assertNull(access.findActiveMembership(1, 9));
    }

    @Test
    void scopeAndOwnerAreFactsWithoutImplicitAuthority() {
        assertEquals(9L, access.findActiveWorkspace(1).getOwnerId());
        assertNull(access.findActiveMembership(2, 9));
        assertFalse(access.hasMinimumRole(2, 9, "viewer"));
        jdbc.update("DELETE FROM mate_workspace_member WHERE id=19");
        assertFalse(access.hasMinimumRole(1, 9, "viewer"));
    }

    @Test
    void exactRolesAreNotNormalizedOrUpgraded() {
        for (String role :
                java.util.List.of("viewer", "member", "admin", "owner", "ADMIN", "unknown")) {
            jdbc.update("UPDATE mate_workspace_member SET role=? WHERE id=19", role);
            assertEquals(
                    java.util.List.of("admin", "owner").contains(role),
                    access.hasMinimumRole(1, 9, "admin"));
        }
        jdbc.update("UPDATE mate_workspace_member SET role=NULL WHERE id=19");
        assertThrows(NullPointerException.class, () -> access.hasMinimumRole(1, 9, "viewer"));
    }

    @Test
    void workspaceDeletionIsRecheckedAndNullFlagRemainsActive() {
        assertNotNull(access.findActiveWorkspace(1));
        jdbc.update("UPDATE mate_workspace SET deleted=1 WHERE id=1");
        assertNull(access.findActiveWorkspace(1));
        jdbc.update("UPDATE mate_workspace SET deleted=NULL WHERE id=1");
        assertNotNull(access.findActiveWorkspace(1));
        assertNull(access.findActiveWorkspace(99));
    }
}

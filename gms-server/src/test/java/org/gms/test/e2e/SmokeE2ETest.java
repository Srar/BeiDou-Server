package org.gms.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.gms.dao.entity.AccountsDO;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E2E 基础设施冒烟：容器可达、Spring 上下文可起、核心表存在、最小登录链路通。
 * 本类是其他 E2E 测试类的前置就绪信号。
 * Flyway 迁移完整性与失败记录断言见 {@link FlywayMigrationE2ETest}，此处不重复。
 */
class SmokeE2ETest extends AbstractMySQLE2ETest {

    @Test
    void containerIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, one);
    }

    @Test
    void coreTablesExist() {
        for (String table : new String[]{"accounts", "characters", "game_config", "inventoryitems", "shops"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'beidou' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, "核心表应存在：" + table);
        }
    }

    @Test
    void gmLoginAndProtectedEndpointEndToEnd() throws Exception {
        AccountsDO gm = createAccount("gm", "smokePass1", true);
        String token = login(gm.getName(), "smokePass1");

        JsonNode info = getJson("/account/v1/info", token);
        assertEquals(SUCCESS_CODE, info.path("code").asInt(), "携带 GM token 访问受保护接口应成功：" + info);
        assertEquals(gm.getName(), info.path("data").path("name").asText());
    }
}

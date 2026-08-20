package org.gms.test.e2e;

import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移与数据库结构的端到端验证（比 SmokeE2ETest 更深入，不重复其表存在性断言）。
 * 覆盖：迁移历史完整性、乱序文件名与版本序迁移、代表表列结构、seed 数据与列默认值、唯一索引约束。
 * 所有断言均依据 {@code db/migration/*.sql} 的实际 DDL/DML 编写；本类自造的账号数据在用例末尾全部清理。
 */
class FlywayMigrationE2ETest extends AbstractMySQLE2ETest {

    /** 应用库名，与 Testcontainers 容器初始化参数一致。 */
    private static final String SCHEMA = "beidou";

    /**
     * 迁移历史完整性：flyway_schema_history 记录数 = classpath 迁移脚本文件数（动态计数，不硬编码），
     * 且全部 success = 1、无失败记录。
     */
    @Test
    void migrationHistoryMatchesScriptCountAndAllSucceeded() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        int expected = scripts.length;
        assertTrue(expected > 0, "迁移脚本目录不应为空");

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        Integer succeeded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertNotNull(total);
        assertNotNull(succeeded);
        assertNotNull(failed);
        assertEquals(expected, total, "迁移历史记录数应等于迁移脚本文件数");
        assertEquals(expected, succeeded, "所有迁移脚本都应执行成功");
        assertEquals(0, failed, "不应存在失败的迁移记录");
    }

    /**
     * 乱序文件命名与版本序迁移验证：迁移脚本目录中确实存在"文件序与版本序不一致"的脚本
     * （如 V1.0.1__create_alliance.sql 按文件名排序位于 V1.0.19 之后）。
     * Flyway 在<b>干净库</b>上首次迁移时严格按版本号升序应用（out-of-order 标志只影响对已迁移库
     * 的增量补跑），因此乱序脚本的 installed_rank 仍按版本序排列：
     * 低版本（1.0.1）的 installed_rank 必须<b>小于</b>先到高版本（1.0.19）。
     */
    @Test
    void outOfOrderFilenamesStillMigrateInVersionOrderOnCleanDatabase() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        List<String> filenames = Arrays.stream(scripts)
                .map(Resource::getFilename)
                .sorted()
                .toList();

        // 按文件序扫描，记录每个"版本号小于此前出现过的最大版本"的乱序脚本及其参照的高版本
        List<String[]> outOfOrderPairs = new ArrayList<>();
        String maxVersionSoFar = null;
        for (String filename : filenames) {
            String version = extractVersion(filename);
            if (maxVersionSoFar != null && compareVersions(version, maxVersionSoFar) < 0) {
                outOfOrderPairs.add(new String[]{version, maxVersionSoFar});
            } else {
                maxVersionSoFar = version;
            }
        }
        assertFalse(outOfOrderPairs.isEmpty(), "应存在文件序与版本序不一致的迁移脚本");

        for (String[] pair : outOfOrderPairs) {
            int lowerVersionRank = installedRank(pair[0]);
            int higherVersionRank = installedRank(pair[1]);
            assertTrue(lowerVersionRank < higherVersionRank,
                    "干净库首次迁移应按版本号升序应用：低版本 " + pair[0] + " 的 installed_rank 应小于高版本 " + pair[1]);
        }
    }

    /** 代表表关键列抽查：列清单取自对应 migration 脚本的 DDL，通过 information_schema.columns 逐一断言存在。 */
    @Test
    void representativeTableColumnsMatchDdl() {
        Map<String, List<String>> expectedColumns = Map.of(
                "accounts", Arrays.asList("id", "name", "password", "pin", "loggedin", "createdat", "birthday", "banned", "webadmin", "language"),
                "characters", Arrays.asList("id", "accountid", "world", "name", "level", "exp", "hp", "mp", "maxhp", "maxmp", "meso", "gm", "buddyCapacity", "createdate"),
                "game_config", Arrays.asList("id", "config_type", "config_sub_type", "config_clazz", "config_code", "config_value", "config_desc", "update_time"),
                "inventoryitems", Arrays.asList("inventoryitemid", "type", "characterid", "accountid", "itemid", "inventorytype", "position", "quantity", "owner", "petid", "expiration", "giftFrom"),
                "shops", Arrays.asList("shopid", "npcid"),
                "notes", Arrays.asList("id", "to", "from", "message", "TIMESTAMP", "fame", "deleted"));

        for (Map.Entry<String, List<String>> entry : expectedColumns.entrySet()) {
            String table = entry.getKey();
            for (String column : entry.getValue()) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema = ? AND table_name = ? AND LOWER(column_name) = LOWER(?)",
                        Integer.class, SCHEMA, table, column);
                assertEquals(1, count, "表 " + table + " 应存在列 " + column);
            }
        }
    }

    /**
     * seed 数据抽查：game_config 含 world/server 类型种子行，且 V1.8.5 的 UPDATE、V1.8.8 的追加、V1.11.2 的 INSERT 生效；
     * accounts 表仅插入 name/password 两列时，其余列取 V1.0.0 DDL 定义的 DEFAULT 值。自造账号末尾清理。
     */
    @Test
    void gameConfigSeedDataAndAccountColumnDefaults() {
        Integer worldRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE config_type = 'world'", Integer.class);
        Integer serverRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE config_type = 'server'", Integer.class);
        assertNotNull(worldRows);
        assertNotNull(serverRows);
        assertTrue(worldRows > 0, "V1.7.0 应写入 world 类型配置种子数据");
        assertTrue(serverRows > 0, "V1.7.0 应写入 server 类型配置种子数据");

        // V1.8.3 插入 use_enable_party_level_limit_lift='true'，V1.8.5 将其更新为 'false'
        String partyLevelLimit = jdbcTemplate.queryForObject(
                "SELECT config_value FROM game_config WHERE config_code = 'use_enable_party_level_limit_lift'", String.class);
        assertEquals("false", partyLevelLimit, "V1.8.5 应把 use_enable_party_level_limit_lift 更新为 false");

        // V1.8.8 把 1300005 追加进 npcs_scriptable 的 JSON 值
        String npcsScriptable = jdbcTemplate.queryForObject(
                "SELECT config_value FROM game_config WHERE config_code = 'npcs_scriptable'", String.class);
        assertTrue(npcsScriptable.contains("1300005"), "V1.8.8 应把 1300005 追加进 npcs_scriptable 配置");

        // V1.11.2 插入 damage_ranking 配置
        Integer damageRanking = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE config_code = 'damage_ranking'", Integer.class);
        assertEquals(1, damageRanking, "V1.11.2 应插入 damage_ranking 配置");

        // 最小行插入，验证 V1.0.0 的列默认值
        String name = uniqueName("dflt");
        jdbcTemplate.update("INSERT INTO accounts (name, password) VALUES (?, ?)", name, "dfltPass1");
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT createdat, birthday, pin, CAST(banned AS UNSIGNED) AS banned, CAST(language AS UNSIGNED) AS language,"
                            + " CAST(gender AS UNSIGNED) AS gender, CAST(characterslots AS UNSIGNED) AS characterslots"
                            + " FROM accounts WHERE name = ?", name);

            Object createdAt = row.get("createdat");
            assertNotNull(createdAt, "createdat 应取 DEFAULT CURRENT_TIMESTAMP");
            // TIMESTAMP 列的客户端时区换算受 JDBC serverTimezone/connectionTimeZone 影响
            // （容器内 MySQL 为 UTC 时区，驱动按 Asia/Shanghai 解释会整体偏移 8 小时），
            // 与 JVM 时钟直接相减不可靠；改为在数据库侧用 CURRENT_TIMESTAMP 做差值，
            // 验证"默认值 = 插入时刻"这一语义本身。
            Integer driftSeconds = jdbcTemplate.queryForObject(
                    "SELECT ABS(TIMESTAMPDIFF(SECOND, createdat, CURRENT_TIMESTAMP)) FROM accounts WHERE name = ?",
                    Integer.class, name);
            assertNotNull(driftSeconds, "createdat 与数据库当前时间的差值不应为空");
            assertTrue(driftSeconds < 10 * 60, "createdat 默认值应接近数据库当前时间，实际偏差 " + driftSeconds + " 秒");
            assertEquals("2005-05-11", ((Date) row.get("birthday")).toString(), "birthday 应取 DEFAULT '2005-05-11'");
            assertEquals("", row.get("pin"), "pin 应取 DEFAULT ''");
            assertEquals(0, ((Number) row.get("banned")).intValue(), "banned 应取 DEFAULT 0");
            assertEquals(3, ((Number) row.get("language")).intValue(), "language 应取 DEFAULT 3");
            assertEquals(10, ((Number) row.get("gender")).intValue(), "gender 应取 DEFAULT 10");
            assertEquals(3, ((Number) row.get("characterslots")).intValue(), "characterslots 应取 DEFAULT 3");
        } finally {
            jdbcTemplate.update("DELETE FROM accounts WHERE name = ?", name);
        }
    }

    /**
     * 外键/索引抽查：accounts.name 唯一索引存在（information_schema.statistics），
     * 插入重复 name 应抛 DuplicateKeyException。自造账号末尾清理。
     */
    @Test
    void accountsNameUniqueIndexRejectsDuplicateInsert() {
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics"
                        + " WHERE table_schema = ? AND table_name = 'accounts' AND LOWER(index_name) = 'name' AND non_unique = 0",
                Integer.class, SCHEMA);
        assertEquals(1, indexCount, "accounts.name 应存在唯一索引");

        String name = uniqueName("uniq");
        jdbcTemplate.update("INSERT INTO accounts (name, password) VALUES (?, ?)", name, "uniqPass1");
        try {
            assertThrows(DuplicateKeyException.class,
                    () -> jdbcTemplate.update("INSERT INTO accounts (name, password) VALUES (?, ?)", name, "uniqPass2"),
                    "插入重复 name 应抛出 DuplicateKeyException");
        } finally {
            jdbcTemplate.update("DELETE FROM accounts WHERE name = ?", name);
        }
    }

    // ---------- 私有辅助 ----------

    /** 从形如 V1.0.1__create_alliance.sql 的文件名中提取版本段（如 1.0.1）。 */
    private String extractVersion(String filename) {
        return filename.substring(filename.indexOf('V') + 1, filename.indexOf("__"));
    }

    /** 按点分段比较语义版本号，返回负数/零/正数。 */
    private static int compareVersions(String left, String right) {
        int[] leftParts = Arrays.stream(left.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int[] rightParts = Arrays.stream(right.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? leftParts[i] : 0;
            int rightPart = i < rightParts.length ? rightParts[i] : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    /** 查询某版本的 installed_rank，要求该版本在 flyway_schema_history 中有且仅有一条记录。 */
    private int installedRank(String version) {
        List<Integer> ranks = jdbcTemplate.queryForList(
                "SELECT installed_rank FROM flyway_schema_history WHERE version = ?", Integer.class, version);
        assertEquals(1, ranks.size(), "版本 " + version + " 应有且仅有一条迁移历史记录");
        return ranks.get(0);
    }
}

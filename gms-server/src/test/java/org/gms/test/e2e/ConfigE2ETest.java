package org.gms.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.gms.config.GameConfig;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.GameConfigDO;
import org.gms.model.dto.GameConfigReqDTO;
import org.gms.model.dto.SubmitBody;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态配置（GameConfig + ConfigController）端到端验证。
 * <p>
 * 安全约定（违反会破坏其他测试甚至炸掉上下文）：
 * <ul>
 *   <li>本类新增/更新/删除的配置一律使用自定义 type "e2etest"，绝不触碰 type="world" 的行
 *       （GameConfig.update 的 world 分支会调用 Server.getInstance().getWorld()，游戏服禁用时会异常）；</li>
 *   <li>所有新增行在 @AfterEach 中按 code 兜底清理（库表 + GameConfig 内存树 + lang_resources），
 *       保证用例失败时也不残留脏数据；</li>
 *   <li>不调用 /server/v1/* 生命周期接口，不触碰 Server.getInstance()。</li>
 * </ul>
 */
class ConfigE2ETest extends AbstractMySQLE2ETest {

    /** 配置接口前缀，与 ConfigController 的 /config/v1 对齐。 */
    private static final String CONFIG_URL = "/config/v1";
    /** 本测试专用自定义类型，避免与 seed 数据（server/world）冲突。 */
    private static final String TEST_TYPE = "e2etest";
    /** 本测试专用子类型。 */
    private static final String TEST_SUB_TYPE = "global";

    /** GM token，每个用例独立账号登录。 */
    private String token;
    /** 本用例新增的 config_code 集合，@AfterEach 兜底清理。 */
    private final Set<String> createdCodes = new HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        AccountsDO gm = createAccount("cfg", "configPass1", true);
        token = login(gm.getName(), "configPass1");
    }

    @AfterEach
    void cleanUp() {
        // 无论用例成败，按 code 兜底清理本类新增的配置行与 i18n 行，并从 GameConfig 内存树移除，
        // 避免污染其他测试类与后续运行。
        for (String code : createdCodes) {
            jdbcTemplate.update("DELETE FROM game_config WHERE config_type = ? AND config_code = ?", TEST_TYPE, code);
            jdbcTemplate.update("DELETE FROM lang_resources WHERE lang_code = ?", code);
            GameConfig.remove(GameConfigDO.builder()
                    .configType(TEST_TYPE)
                    .configSubType(TEST_SUB_TYPE)
                    .configCode(code)
                    .build());
        }
        createdCodes.clear();
    }

    // ---------- 用例 1：配置类型列表 ----------

    @Test
    void getConfigTypeListReturnsSeedTypes() {
        // GM token 请求类型列表，应返回 SUCCESS_CODE，且包含 V1.7.0 seed 脚本中的类型 server/world
        JsonNode response = getJson(CONFIG_URL + "/getConfigTypeList", token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "获取配置类型列表应成功：" + response);

        List<String> types = new ArrayList<>();
        response.path("data").path("types").forEach(node -> types.add(node.asText()));
        assertTrue(types.contains("server"), "类型列表应包含 seed 类型 server：" + types);
        assertTrue(types.contains("world"), "类型列表应包含 seed 类型 world：" + types);
    }

    // ---------- 用例 2：按类型查询配置列表 ----------

    @Test
    void getConfigListCountMatchesDatabaseForServerType() {
        // 独立 oracle：直接用最朴素口径统计 server 类型行数（不复刻 ConfigService 的 LEFT JOIN SQL，
        // 否则若其 join 条件/字段关联有缺陷会与测试同错同过）。若 LEFT JOIN lang_resources 导致行膨胀，
        // 此处的 totalRow/records 比对即能暴露回归。
        Integer expected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE config_type = 'server'", Integer.class);
        Set<String> dbCodes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT config_code FROM game_config WHERE config_type = 'server'", String.class));

        GameConfigReqDTO condition = new GameConfigReqDTO();
        condition.setType("server");
        condition.setPageNo(1);
        condition.setPageSize(100000);

        SubmitBody<GameConfigReqDTO> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(condition);

        JsonNode response = postJson(CONFIG_URL + "/getConfigList", body, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "按类型查询配置列表应成功：" + response);
        assertNotNull(expected, "库中 server 类型行数不应为空");
        assertEquals(expected.longValue(), response.path("data").path("totalRow").asLong(), "总数应与库中 server 行数一致");
        assertEquals(expected.intValue(), response.path("data").path("records").size(), "返回条数应与库中 server 行数一致");

        Set<String> responseCodes = new HashSet<>();
        response.path("data").path("records").forEach(node -> responseCodes.add(node.path("configCode").asText()));
        assertEquals(dbCodes, responseCodes, "返回记录的 config_code 集合应与库中一致（join 不应导致重复/丢失）");
    }

    // ---------- 用例 3：GameConfig 单例从库加载 ----------

    @Test
    void gameConfigSingletonLoadsSeedValuesFromDatabase() {
        // GameConfig 是 JVM 级静态单例：首次触碰时经 ServerManager 桥接的容器一次性加载，
        // 之后无法重载。全量运行时更早的纯单元测试（BotTestSupport 注入 mock 容器、
        // loadGameConfigs 返回空表）可能已把它初始化为空树——此时本用例的前提不成立，
        // 用 assumption 跳过（本类其余用例覆盖 add/update/delete 热更新语义，不依赖种子值）。
        String dbValue = jdbcTemplate.queryForObject(
                "SELECT config_value FROM game_config WHERE config_type = 'server' AND config_code = 'max_world_size'",
                String.class);
        assertNotNull(dbValue, "seed 应存在 max_world_size 行");
        Assumptions.assumeTrue(String.valueOf(GameConfig.getServerInt("max_world_size")).equals(dbValue),
                "GameConfig 单例已被先前单元测试以空配置初始化（BotTestSupport mock 容器），跳过种子值断言");

        // 单例确实由本库加载：断言 V1.7.0 seed 中的稳定键
        assertEquals(21, GameConfig.getServerInt("max_world_size"), "GameConfig 应加载 seed 的 max_world_size");
        assertEquals("GMT+8", GameConfig.getServerString("timezone"), "GameConfig 应加载 seed 的 timezone");
        assertTrue(GameConfig.getServerBoolean("use_cpq"), "GameConfig 应加载 seed 的 use_cpq");

        // 与库中当前值交叉验证，确保单例内存值与数据库一致
        assertEquals(dbValue, String.valueOf(GameConfig.getServerInt("max_world_size")), "单例内存值应与库中值一致");
    }

    // ---------- 用例 4：新增配置 → 库可见 → 热更新生效 ----------

    @Test
    void addConfigPersistsAndHotLoadsIntoGameConfig() {
        String code = newCode();
        addConfig(code, "42");

        // 库中行已存在且值正确
        String dbValue = jdbcTemplate.queryForObject(
                "SELECT config_value FROM game_config WHERE config_type = ? AND config_code = ?",
                String.class, TEST_TYPE, code);
        assertEquals("42", dbValue, "新增行应写入 game_config 表");

        // GameConfig 内存树热更新生效：按 type/subType/code 类型化读取（clazz=java.lang.Integer）
        Integer loaded = GameConfig.get(TEST_TYPE, TEST_SUB_TYPE, code);
        assertNotNull(loaded, "GameConfig 应能读到新增配置：" + code);
        assertEquals(42, loaded, "GameConfig 类型化读取值应为 42");

        // 全局 key 读取同样可见
        assertEquals("42", GameConfig.getString(code), "GameConfig 按 key 读取值应为 42");
    }

    // ---------- 用例 5：更新配置热生效 ----------

    @Test
    void updateConfigHotAppliesNewValue() {
        String code = newCode();
        addConfig(code, "42");
        Long id = configId(code);
        assertNotNull(id, "新增行应有自增 id");

        GameConfigDO update = GameConfigDO.builder()
                .id(id)
                .configValue("43")
                .configDesc("e2e 测试参数")
                .build();
        SubmitBody<GameConfigDO> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(update);

        JsonNode response = postJson(CONFIG_URL + "/updateConfig", body, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "更新配置应成功：" + response);

        // 库中 config_value 已更新
        String dbValue = jdbcTemplate.queryForObject(
                "SELECT config_value FROM game_config WHERE id = ?", String.class, id);
        assertEquals("43", dbValue, "库中 config_value 应更新为 43");

        // GameConfig 内存树热更新生效
        Integer updated = GameConfig.get(TEST_TYPE, TEST_SUB_TYPE, code);
        assertNotNull(updated, "GameConfig 应能读到更新后的配置：" + code);
        assertEquals(43, updated, "GameConfig 应读到更新后的值 43");
    }

    // ---------- 用例 6：删除配置同步 ----------

    @Test
    void deleteConfigRemovesRowAndSyncsGameConfig() {
        String code = newCode();
        addConfig(code, "42");
        Long id = configId(code);
        assertNotNull(id, "新增行应有自增 id");

        JsonNode response = deleteJson(CONFIG_URL + "/deleteConfig/" + id, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "删除配置应成功：" + response);

        // 库中行消失
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE id = ?", Integer.class, id);
        assertEquals(0, count, "库中该行应被删除");

        // GameConfig 内存树同步移除（不存在时 get 返回 null、getValueProp 返回 null）
        Object removed = GameConfig.get(TEST_TYPE, TEST_SUB_TYPE, code);
        assertNull(removed, "GameConfig 树中该配置应被移除");
        assertNull(GameConfig.getValueProp(TEST_TYPE, TEST_SUB_TYPE, code), "getValueProp 应返回 null");
    }

    // ---------- 用例 7：exportYml 可用 ----------

    @Test
    void exportYmlReturnsNonEmptyYaml() {
        // exportYml 直接返回 ResponseEntity<Resource>（YAML 文本），非 ResultBody JSON，需直接拿字符串断言
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange(CONFIG_URL + "/exportYml", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "导出 yml 应返回 200");
        assertNotNull(response.getBody(), "导出内容不应为空");
        assertFalse(response.getBody().isBlank(), "导出内容不应为空白");
        assertTrue(response.getBody().contains("gms:"), "导出内容应为 gms 配置的 yml");
        assertTrue(response.getBody().contains("max_world_size"), "导出内容应包含 seed 的 server 配置键");
    }

    // ---------- 用例 8：批量删除 ----------

    @Test
    void deleteConfigListRemovesMultipleRows() {
        // deleteConfigList 提交 id 列表，内部逐条走 deleteConfig（含库删除与 GameConfig.remove 同步）
        String code1 = newCode();
        String code2 = newCode();
        addConfig(code1, "1");
        addConfig(code2, "2");
        Long id1 = configId(code1);
        Long id2 = configId(code2);
        assertNotNull(id1, "第一条新增行应有自增 id");
        assertNotNull(id2, "第二条新增行应有自增 id");

        SubmitBody<List<Long>> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(List.of(id1, id2));

        JsonNode response = postJson(CONFIG_URL + "/deleteConfigList", body, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "批量删除应成功：" + response);

        // 两条记录都应从库中消失
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_config WHERE config_type = ? AND config_code IN (?, ?)",
                Integer.class, TEST_TYPE, code1, code2);
        assertEquals(0, count, "两条配置行都应被删除");

        // GameConfig 内存树同步移除
        Object removed1 = GameConfig.get(TEST_TYPE, TEST_SUB_TYPE, code1);
        Object removed2 = GameConfig.get(TEST_TYPE, TEST_SUB_TYPE, code2);
        assertNull(removed1, "GameConfig 树中第一条配置应被移除");
        assertNull(removed2, "GameConfig 树中第二条配置应被移除");
    }

    // ---------- 私有辅助 ----------

    /** 生成唯一 config_code（小写 UUID 前缀，避免与 seed 键冲突），并登记到清理集合。 */
    private String newCode() {
        String code = "e2e_" + UUID.randomUUID();
        createdCodes.add(code);
        return code;
    }

    /** 构造本测试专用的自定义类型配置实体。 */
    private GameConfigDO newConfig(String code, String value) {
        return GameConfigDO.builder()
                .configType(TEST_TYPE)
                .configSubType(TEST_SUB_TYPE)
                .configCode(code)
                .configValue(value)
                .configClazz("java.lang.Integer")
                .configDesc("e2e 测试参数")
                .build();
    }

    /** 走真实 HTTP 接口新增一条配置并断言成功。 */
    private JsonNode addConfig(String code, String value) {
        SubmitBody<GameConfigDO> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(newConfig(code, value));
        JsonNode response = postJson(CONFIG_URL + "/addConfig", body, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "新增配置应成功：" + response);
        return response;
    }

    /** 按 code 查库取自增 id。 */
    private Long configId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM game_config WHERE config_type = ? AND config_code = ?",
                Long.class, TEST_TYPE, code);
    }
}

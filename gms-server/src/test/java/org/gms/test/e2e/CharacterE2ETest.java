package org.gms.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.dao.mapper.CharactersMapper;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色查询链路端到端验证（安全子集，游戏服已禁用）。
 * <p>
 * 覆盖接口：GET /character/v1/account/{accountId}、DELETE /character/v1/{cid}。
 * 行为依据：
 * <ul>
 *   <li>{@code getCharacterListByAccountId}：纯 DB 查询 + {@code findOnlineCharacter}
 *       遍历 {@code Server.getInstance().getWorlds()}（游戏服禁用下为空列表，不 NPE），
 *       返回 {@code List<CharacterListItemDTO>}，无角色时为空列表。</li>
 *   <li>{@code deleteCharacterWithOnlineCheck}：{@code prepareCharacterOffline} 在线查找同样
 *       遍历空 worlds 列表后回退 DB；新造角色无 guild/buddies 关联，删除路径全部为纯 DB
 *       操作；{@code safeDeleteCharacterEntry} 已兜底捕获登录缓存的 NPE。</li>
 * </ul>
 * 禁测清单（依赖游戏服状态，游戏服禁用下语义不明或不安全，故不写用例）：
 * <ul>
 *   <li>POST /character/v1/updateRate、/resetRate、/resetRates：依赖 {@code Server.getInstance()}
 *       遍历 World/Channel 内存中的在线 Character 才能生效，游戏服禁用下必然抛
 *       {@code BizException.illegalArgument}（找不到在线角色），语义不明，不测。</li>
 *   <li>POST /character/v1/online/list：{@code getChrOnlineList} 直接调用
 *       {@code Server.getInstance().getWorld(request.getWorld()).getPlayerStorage()}，
 *       游戏服禁用下 {@code getWorld} 返回 null，必然 NPE（接口 500），无法安全测试，跳过。</li>
 *   <li>/server/v1/* 生命周期接口：E2E 基类明令禁止触碰。</li>
 * </ul>
 */
class CharacterE2ETest extends AbstractMySQLE2ETest {

    @Autowired
    private CharactersMapper charactersMapper;

    /**
     * 直接插入一条最小合法 characters 行（DDL 见 V1.0.6__create_characters.sql：
     * 所有 NOT NULL 列均有默认值，仅需显式给 accountid/name 等断言依赖字段）。
     * MyBatis-Flex 会回填自增主键 id。
     */
    private CharactersDO insertCharacter(int accountId) {
        CharactersDO chr = CharactersDO.builder()
                .accountid(accountId)
                .name(uniqueName("chr"))
                .level(10)
                .job(0)
                .world(0)
                .gm(0)
                .fame(0)
                .meso(0)
                .build();
        charactersMapper.insertSelective(chr);
        return chr;
    }

    /** 断言角色列表数组包含指定 id 与 name 的角色。 */
    private void assertContainsCharacter(JsonNode data, int cid, String name) {
        boolean found = false;
        for (JsonNode item : data) {
            if (item.path("id").asInt() == cid && name.equals(item.path("name").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "角色列表应包含 id=" + cid + "、name=" + name + " 的角色，实际响应：" + data);
    }

    /** 按账号查角色列表：GM 账号下插入角色后，接口应返回含该角色的列表。 */
    @Test
    void getCharacterListByAccountIdReturnsInsertedCharacter() throws Exception {
        // 造 GM 账号并登录拿 token
        AccountsDO gm = createAccount("ch1", "charPass1", true);
        String token = login(gm.getName(), "charPass1");
        // 直接插入最小合法角色行
        CharactersDO chr = insertCharacter(gm.getId());

        JsonNode response = getJson("/character/v1/account/" + gm.getId(), token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "按账号查角色列表应成功：" + response);
        JsonNode data = response.path("data");
        assertTrue(data.isArray(), "data 应为角色数组：" + response);
        assertContainsCharacter(data, chr.getId(), chr.getName());

        // 自清：直接删掉自造角色，避免残留
        jdbcTemplate.update("DELETE FROM characters WHERE id = ?", chr.getId());
    }

    /** 无角色的新账号返回空列表。 */
    @Test
    void getCharacterListByAccountIdReturnsEmptyListForNewAccount() throws Exception {
        // 新账号（未插入任何角色）
        AccountsDO gm = createAccount("ch2", "charPass2", true);
        String token = login(gm.getName(), "charPass2");

        JsonNode response = getJson("/character/v1/account/" + gm.getId(), token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "查无角色账号应成功：" + response);
        JsonNode data = response.path("data");
        assertTrue(data.isArray() && data.size() == 0, "无角色账号应返回空列表：" + response);
    }

    /** 删除角色：删除自造角色后，characters 表中该行应消失。 */
    @Test
    void deleteCharacterRemovesRowFromDatabase() throws Exception {
        // 造 GM 账号与角色
        AccountsDO gm = createAccount("ch4", "charPass4", true);
        String token = login(gm.getName(), "charPass4");
        CharactersDO chr = insertCharacter(gm.getId());

        // 删除前该行应存在
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM characters WHERE id = ?", Integer.class, chr.getId());
        assertEquals(1, before, "删除前角色行应存在");

        JsonNode response = deleteJson("/character/v1/" + chr.getId(), token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "删除角色应成功：" + response);

        // 删除后该行应消失
        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM characters WHERE id = ?", Integer.class, chr.getId());
        assertEquals(0, after, "删除后角色行应消失");
    }

    /** 查询不存在的账号：返回成功且空列表（源码：查不到即空列表，不抛错）。 */
    @Test
    void getCharacterListByUnknownAccountIdReturnsEmptyList() throws Exception {
        AccountsDO gm = createAccount("ch5", "charPass5", true);
        String token = login(gm.getName(), "charPass5");

        // 动态取"必然不存在"的账号 id（最大 id + 偏移），避免硬编码与未来自增撞车
        Integer maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 100000 FROM accounts", Integer.class);
        assertNotNull(maxId, "最大账号 id 不应为空");

        JsonNode response = getJson("/character/v1/account/" + maxId, token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "查不存在的账号应成功：" + response);
        JsonNode data = response.path("data");
        assertTrue(data.isArray() && data.size() == 0, "不存在的账号应返回空列表：" + response);
    }
}

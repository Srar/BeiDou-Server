package org.gms.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.gms.client.DefaultDates;
import org.gms.dao.entity.AccountsDO;
import org.gms.exception.BizExceptionEnum;
import org.gms.model.dto.AddAccountDTO;
import org.gms.model.dto.SubmitBody;
import org.gms.model.dto.UpdateAccountByGmDTO;
import org.gms.model.dto.UpdateAccountByUserDTO;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号管理链路端到端测试：注册 / 列表分页与过滤 / 当前用户信息 / 用户改资料 / GM 更新 / 封禁解封 / 重置在线状态 / 删除。
 * <p>
 * 只有 webadmin=1 的账号能通过 JWT 过滤器访问受保护接口（见 UserDetailsServiceImpl），
 * 因此管理操作用 GM token；普通账号 token 只用于登录链路验证。
 * 所有账号名通过 uniqueName()/createAccount() 生成，满足 accounts.name 唯一约束与 13 字符上限。
 */
class AccountE2ETest extends AbstractMySQLE2ETest {

    // ---------- 请求体与查询辅助 ----------

    /** 包一层 SubmitBody 信封（requestId 随机）。 */
    private <T> SubmitBody<T> submit(T data) {
        SubmitBody<T> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(data);
        return body;
    }

    /** 构造注册请求体，字段与 AddAccountDTO 及 addAccount 校验一致（language 必填）。 */
    private SubmitBody<AddAccountDTO> registerBody(String name, String password) {
        AddAccountDTO dto = new AddAccountDTO();
        dto.setName(name);
        dto.setPassword(password);
        dto.setBirthday(Date.valueOf(DefaultDates.getBirthday()));
        dto.setLanguage(3);
        return submit(dto);
    }

    /** 登录但不强制成功断言，供“应失败”场景使用。 */
    private JsonNode rawLogin(String username, String password) {
        return postJson("/auth/v1/login", submit(Map.of("username", username, "password", password)), null);
    }

    /** 按名查库取账号 id（不存在直接断言失败）。 */
    private int accountId(String name) {
        AccountsDO account = accountsMapper.selectOneByName(name);
        assertNotNull(account, "账号应已入库：" + name);
        return account.getId();
    }

    // ---------- 用例 ----------

    /**
     * GM 注册普通账号并登录：注册成功（20000），普通账号（webadmin=0）可直接登录拿 token。
     */
    @Test
    void gmRegisterAccountAndLogin() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass1", true);
        String gmToken = login(gm.getName(), "gmPass1");

        // GM 注册普通账号
        String newName = uniqueName("reg");
        JsonNode register = postJson("/account/v1", registerBody(newName, "regPass1"), gmToken);
        assertEquals(SUCCESS_CODE, register.path("code").asInt(), "GM 注册账号应成功：" + register);
        trackAccount(newName);

        // 普通账号已入库且可登录（AuthService 登录不校验 webadmin）
        AccountsDO registered = accountsMapper.selectOneByName(newName);
        assertNotNull(registered, "注册的账号应已入库");
        assertEquals(newName, registered.getName(), "库中账号名应与注册名一致");
        String userToken = login(newName, "regPass1");
        assertNotNull(userToken, "普通账号应能登录拿到 token");
    }

    /**
     * 重复名注册失败：addAccount 对已存在名字抛 ILLEGAL_PARAMETERS（40002）。
     */
    @Test
    void duplicateNameRegistrationFails() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass2", true);
        String gmToken = login(gm.getName(), "gmPass2");

        String name = uniqueName("dup");
        JsonNode first = postJson("/account/v1", registerBody(name, "dupPass1"), gmToken);
        assertEquals(SUCCESS_CODE, first.path("code").asInt(), "首次注册应成功：" + first);
        trackAccount(name);

        // 同名再次注册 → 非成功码，且为参数非法错误码（见 AccountService.addAccount 的 requireNull 校验）
        JsonNode second = postJson("/account/v1", registerBody(name, "dupPass1"), gmToken);
        assertNotEquals(SUCCESS_CODE, second.path("code").asInt(), "重复名注册不应成功：" + second);
        assertEquals(BizExceptionEnum.ILLEGAL_PARAMETERS.getResultCode(), second.path("code").asInt(),
                "重复名注册应返回 ILLEGAL_PARAMETERS 错误码：" + second);
    }

    /**
     * 账号列表分页与过滤：MyBatis-Flex Page 序列化字段为 totalRow/records；
     * name 过滤为 like 子串匹配，传完整名字应恰好命中该账号。
     */
    @Test
    void accountListPaginationAndNameFilter() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass3", true);
        String gmToken = login(gm.getName(), "gmPass3");

        // 注册 3 个不同前缀的账号
        String aName = uniqueName("pga");
        String bName = uniqueName("pgb");
        String cName = uniqueName("pgc");
        for (String name : new String[]{aName, bName, cName}) {
            JsonNode reg = postJson("/account/v1", registerBody(name, "listPass1"), gmToken);
            assertEquals(SUCCESS_CODE, reg.path("code").asInt(), "注册应成功：" + reg);
            trackAccount(name);
        }

        // 分页：page=1&size=2，共享库中可能还有其他测试造的数据，总数只断言下限
        JsonNode page = getJson("/account/v1?page=1&size=2", gmToken);
        assertEquals(SUCCESS_CODE, page.path("code").asInt(), "分页查询应成功：" + page);
        assertTrue(page.path("data").path("totalRow").asLong() >= 3,
                "总数应至少包含本次新建的 3 个账号：" + page);
        assertEquals(2, page.path("data").path("records").size(), "size=2 时本页应返回 2 条记录：" + page);

        // 按完整名字过滤：后端为 like 子串匹配，唯一名字应恰好命中一条
        JsonNode filtered = getJson("/account/v1?name=" + bName, gmToken);
        assertEquals(SUCCESS_CODE, filtered.path("code").asInt(), "按名过滤应成功：" + filtered);
        int matched = 0;
        for (JsonNode record : filtered.path("data").path("records")) {
            if (bName.equals(record.path("name").asText())) {
                matched++;
            }
        }
        assertEquals(1, matched, "按完整名字过滤应恰好命中该账号：" + filtered);
    }

    /**
     * 当前用户信息：GET /account/v1/info 返回 token 对应用户（getCurrentUser 取 SecurityContext 用户名）。
     */
    @Test
    void currentUserInfoReturnsLoggedInAccount() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass4", true);
        String gmToken = login(gm.getName(), "gmPass4");

        JsonNode info = getJson("/account/v1/info", gmToken);
        assertEquals(SUCCESS_CODE, info.path("code").asInt(), "获取当前用户信息应成功：" + info);
        assertEquals(gm.getName(), info.path("data").path("name").asText(), "info 应返回 token 对应账号名");
    }

    /**
     * 用户改资料（旧密码校验）：PUT /account/v1 语义是“改自己”（getCurrentUser 取 token 对应用户），
     * 因此用 GM 账号自身 token 改自己。旧密码错误 → ILLEGAL_PARAMETERS；
     * 旧密码正确 + 新密码（长度 ≥6 才生效）→ 成功，随后旧密码登录失败、新密码登录成功。
     */
    @Test
    void userUpdatesSelfProfileWithOldPasswordCheck() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass5", true);
        String gmToken = login(gm.getName(), "gmPass5");

        // 旧密码错误 → 失败（updateAccountByUser 先校验旧密码，错误码 ILLEGAL_PARAMETERS）
        UpdateAccountByUserDTO wrong = new UpdateAccountByUserDTO();
        wrong.setOldPwd("wrongOldPwd");
        wrong.setLanguage(3);
        JsonNode fail = putJson("/account/v1", submit(wrong), gmToken);
        assertNotEquals(SUCCESS_CODE, fail.path("code").asInt(), "旧密码错误时改资料不应成功：" + fail);
        assertEquals(BizExceptionEnum.ILLEGAL_PARAMETERS.getResultCode(), fail.path("code").asInt(),
                "旧密码错误应返回 ILLEGAL_PARAMETERS 错误码：" + fail);

        // 旧密码正确 + 新密码（≥6 位）+ email → 成功
        UpdateAccountByUserDTO ok = new UpdateAccountByUserDTO();
        ok.setOldPwd("gmPass5");
        ok.setNewPwd("gmNewPass6");
        ok.setEmail("self@e2e.test");
        ok.setLanguage(3);
        JsonNode success = putJson("/account/v1", submit(ok), gmToken);
        assertEquals(SUCCESS_CODE, success.path("code").asInt(), "旧密码正确时改资料应成功：" + success);

        // 库中 email 已生效
        int gmId = accountId(gm.getName());
        assertEquals("self@e2e.test", jdbcTemplate.queryForObject(
                "SELECT email FROM accounts WHERE id = ?", String.class, gmId), "用户改资料应写入 email");

        // 旧密码登录失败、新密码登录成功
        assertEquals(BizExceptionEnum.ILLEGAL_PARAMETERS.getResultCode(),
                rawLogin(gm.getName(), "gmPass5").path("code").asInt(), "改密后旧密码登录应失败");
        assertNotNull(login(gm.getName(), "gmNewPass6"), "新密码登录应成功");
    }

    /**
     * GM 更新账号：PUT /account/v1/{id}（UpdateAccountByGmDTO），email/rewardpoints/language 等字段落库生效。
     */
    @Test
    void gmUpdatesAccountProfile() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass6", true);
        String gmToken = login(gm.getName(), "gmPass6");

        // 注册目标账号并查库拿 id（newPwd 不填则不改密码）
        String targetName = uniqueName("upd");
        JsonNode reg = postJson("/account/v1", registerBody(targetName, "updPass1"), gmToken);
        assertEquals(SUCCESS_CODE, reg.path("code").asInt(), "注册目标账号应成功：" + reg);
        trackAccount(targetName);
        int targetId = accountId(targetName);

        UpdateAccountByGmDTO dto = new UpdateAccountByGmDTO();
        dto.setEmail("gmupd@e2e.test");
        dto.setRewardpoints(4242);
        dto.setVotepoints(88);
        dto.setLanguage(4);
        JsonNode update = putJson("/account/v1/" + targetId, submit(dto), gmToken);
        assertEquals(SUCCESS_CODE, update.path("code").asInt(), "GM 更新账号应成功：" + update);

        // 查库确认字段生效
        assertEquals("gmupd@e2e.test", jdbcTemplate.queryForObject(
                "SELECT email FROM accounts WHERE id = ?", String.class, targetId), "GM 更新应写入 email");
        assertEquals(4242, jdbcTemplate.queryForObject(
                "SELECT rewardpoints FROM accounts WHERE id = ?", Integer.class, targetId), "GM 更新应写入 rewardpoints");
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT language FROM accounts WHERE id = ?", Integer.class, targetId), "GM 更新应写入 language");
    }

    /**
     * 封禁与解封：ban 写入 banned=1 与 banreason，unban 复位 banned=0。
     * 目标账号无角色时 banAccount 的角色遍历为空，不会触碰游戏服单例（Server.getInstance）。
     * 现状：AuthService.getToken 不校验 banned，封禁账号仍能登录拿 token，此处如实断言当前行为。
     */
    @Test
    void banAndUnbanAccount() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass7", true);
        String gmToken = login(gm.getName(), "gmPass7");

        // 目标账号（无角色）
        AccountsDO target = createAccount("ban", "banPass1", false);
        int targetId = accountId(target.getName());

        // 封禁：body 为 SubmitBody<Map>，携带 reason
        JsonNode ban = putJson("/account/v1/" + targetId + "/ban", submit(Map.of("reason", "e2e违规封禁")), gmToken);
        assertEquals(SUCCESS_CODE, ban.path("code").asInt(), "封禁应成功：" + ban);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT banned FROM accounts WHERE id = ?", Integer.class, targetId), "封禁后 banned 应为 1");
        assertEquals("e2e违规封禁", jdbcTemplate.queryForObject(
                "SELECT banreason FROM accounts WHERE id = ?", String.class, targetId), "封禁原因应写入 banreason");

        // 现状断言：AuthService.getToken 只校验账号密码、不校验 banned，封禁账号仍能登录拿 token
        String bannedToken = login(target.getName(), "banPass1");
        assertNotNull(bannedToken, "现状：封禁账号仍可登录拿到 token（AuthService 不校验 banned）");

        // 解封
        JsonNode unban = putJson("/account/v1/" + targetId + "/unban", null, gmToken);
        assertEquals(SUCCESS_CODE, unban.path("code").asInt(), "解封应成功：" + unban);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT banned FROM accounts WHERE id = ?", Integer.class, targetId), "解封后 banned 应为 0");
    }

    /**
     * 重置在线状态：先把目标账号置为 loggedin=1，PUT /account/v1/{id}/reset/logged 后应回到 0。
     */
    @Test
    void resetLoggedInStatus() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass8", true);
        String gmToken = login(gm.getName(), "gmPass8");

        AccountsDO target = createAccount("rst", "rstPass1", false);
        int targetId = accountId(target.getName());
        // 模拟账号处于在线状态
        jdbcTemplate.update("UPDATE accounts SET loggedin = 1 WHERE id = ?", targetId);

        JsonNode reset = putJson("/account/v1/" + targetId + "/reset/logged", null, gmToken);
        assertEquals(SUCCESS_CODE, reset.path("code").asInt(), "重置在线状态应成功：" + reset);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT loggedin FROM accounts WHERE id = ?", Integer.class, targetId), "重置后 loggedin 应为 0");
    }

    /**
     * 删除账号：DELETE /account/v1/{id} 成功后库中该行消失（独立账号，自造自清）。
     * 注意：CLAUDE.md 提示 deleteAccount 链路的 safeDeleteCharacterEntry 有 NPE 风险，
     * 但该路径仅在账号下存在角色时触发；本用例账号无角色，走纯 DB 级联删除路径，预期成功。
     * 若实际返回失败，如实断言并修正此注释，不要改生产代码。
     */
    @Test
    void deleteAccountRemovesRow() throws Exception {
        AccountsDO gm = createAccount("gm", "gmPass9", true);
        String gmToken = login(gm.getName(), "gmPass9");

        // 注册目标账号（删除类用例用独立账号）
        String targetName = uniqueName("del");
        JsonNode reg = postJson("/account/v1", registerBody(targetName, "delPass1"), gmToken);
        assertEquals(SUCCESS_CODE, reg.path("code").asInt(), "注册目标账号应成功：" + reg);
        trackAccount(targetName);
        int targetId = accountId(targetName);

        JsonNode deleted = deleteJson("/account/v1/" + targetId, gmToken);
        assertEquals(SUCCESS_CODE, deleted.path("code").asInt(), "删除账号应成功：" + deleted);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, targetId);
        assertEquals(0, count, "删除后库中该行应消失");
    }
}

package org.gms.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.gms.dao.entity.AccountsDO;
import org.gms.exception.BizExceptionEnum;
import org.gms.model.dto.SubmitBody;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 登录 / JWT / 鉴权 端到端测试。
 * <p>
 * 基于 Testcontainers MySQL + 真实 Spring Security 链路（AuthTokenFilter → SpringSecurityConfig → AuthEntryPointJwt），
 * 账号一律通过基类 createAccount 生成（自动唯一名、SHA-512 密码哈希），不手工插入固定账号。
 * 不调用 /server/v1/* 生命周期接口，不触碰 Server.getInstance()。
 * <p>
 * 鉴权链路关键事实（断言依据）：
 * <ul>
 *   <li>/auth/** 在 SpringSecurityConfig 中 permitAll，AuthTokenFilter 对 /auth/ 前缀直接放行；其余请求需 authenticated()。</li>
 *   <li>AuthService.getToken 密码错误或账号不存在时经 RequireUtil.requireFalse 抛 BizException(ILLEGAL_PARAMETERS=40002)。</li>
 *   <li>AuthService.refreshToken 解析非法 JWT 抛 MalformedJwtException，由 GlobalExceptionHandler 的 RuntimeException
 *       处理器统一返回 BODY_NOT_MATCH(40000)。</li>
 *   <li>无 token / 伪造 token 访问受保护接口时，AuthEntryPointJwt.commence 调用 sendError(401)，响应体非 JSON。</li>
 *   <li>UserDetailsServiceImpl 仅对 webadmin=1 的账号返回 UserDetails，普通账号返回 null。</li>
 * </ul>
 */
class AuthE2ETest extends AbstractMySQLE2ETest {

    /** 构造登录请求体（SubmitBody 信封），用于断言登录失败场景（基类 login 只适用于成功场景）。 */
    private SubmitBody<Map<String, String>> loginBody(String username, String password) {
        SubmitBody<Map<String, String>> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(Map.of("username", username, "password", password));
        return body;
    }

    @Test
    void gmLoginGetsUsableToken() throws Exception {
        // GM 账号（webadmin=1）：登录成功拿到 token 后，应能通过 AuthTokenFilter 的认证并访问受保护接口
        AccountsDO gm = createAccount("gm", "authPass1", true);
        String token = login(gm.getName(), "authPass1");

        JsonNode info = getJson("/account/v1/info", token);
        assertEquals(SUCCESS_CODE, info.path("code").asInt(), "携带 GM token 访问 /account/v1/info 应成功：" + info);
        assertEquals(gm.getName(), info.path("data").path("name").asText(), "返回的账号名应与登录账号一致");
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        // 密码错误：AuthService.getToken 触发 RequireUtil.requireFalse → BizException(ILLEGAL_PARAMETERS=40002)，
        // GlobalExceptionHandler.bizExceptionHandler 原样返回该错误码
        AccountsDO gm = createAccount("gm", "authPass2", true);

        JsonNode response = postJson("/auth/v1/login", loginBody(gm.getName(), "wrongPass"), null);
        assertEquals(BizExceptionEnum.ILLEGAL_PARAMETERS.getResultCode().intValue(),
                response.path("code").asInt(), "错误密码登录应返回 ILLEGAL_PARAMETERS(40002)：" + response);
    }

    @Test
    void loginFailsForUnknownAccount() throws Exception {
        // 账号不存在：findByName 返回 null，与密码错误同走 requireFalse → ILLEGAL_PARAMETERS(40002)。
        // 用 uniqueName 生成未落库的随机用户名，不 createAccount，保证库中确实无此账号
        String username = uniqueName("nous");

        JsonNode response = postJson("/auth/v1/login", loginBody(username, "anyPass1"), null);
        assertEquals(BizExceptionEnum.ILLEGAL_PARAMETERS.getResultCode().intValue(),
                response.path("code").asInt(), "不存在的账号登录应返回 ILLEGAL_PARAMETERS(40002)：" + response);
    }

    @Test
    void refreshTokenReturnsNewToken() throws Exception {
        // /auth/** 放行，携带有效 token 请求 refreshToken 应签发新 token
        AccountsDO gm = createAccount("gm", "authPass4", true);
        String token = login(gm.getName(), "authPass4");

        JsonNode refresh = getJson("/auth/v1/refreshToken", token);
        assertEquals(SUCCESS_CODE, refresh.path("code").asInt(), "刷新 token 应成功：" + refresh);
        String newToken = refresh.path("data").path("token").asText();
        assertNotNull(newToken, "刷新响应应包含新 token：" + refresh);
        assertFalse(newToken.isBlank(), "新 token 不应为空字符串：" + refresh);
        // 注意：JwtUtils 的 iat/exp 为秒级精度，同一秒内刷新会得到与旧 token 完全相同的串，
        // 因此不比较新旧差异，改为验证刷新结果确实可用（能通过过滤器访问受保护接口）
        JsonNode info = getJson("/account/v1/info", newToken);
        assertEquals(SUCCESS_CODE, info.path("code").asInt(), "刷新后的 token 应能访问受保护接口：" + info);
        assertEquals(gm.getName(), info.path("data").path("name").asText(), "刷新后的 token 应对应原账号");
    }

    @Test
    void refreshTokenRejectsInvalidToken() throws Exception {
        // 非法 token：refreshToken 对非 JWT 串调用 jwtUtils.getUserNameFromJwtToken 抛 MalformedJwtException
        // （RuntimeException 子类），GlobalExceptionHandler 的 RuntimeException 处理器统一返回 BODY_NOT_MATCH(40000)
        JsonNode response = getJson("/auth/v1/refreshToken", "not-a-valid-jwt");
        assertEquals(BizExceptionEnum.BODY_NOT_MATCH.getResultCode().intValue(),
                response.path("code").asInt(), "非法 token 刷新应返回 BODY_NOT_MATCH(40000)：" + response);
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        // 无 Authorization 头：AuthTokenFilter 不设置认证上下文，Spring Security 判定未认证后由
        // AuthEntryPointJwt.commence 调用 sendError(401)，响应体是容器错误页（非 JSON），
        // 因此直接用 rest.exchange 断言 HTTP 状态码，不用 exchangeJson
        ResponseEntity<String> response = rest.exchange("/account/v1/info", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), "无 token 访问受保护接口应返回 HTTP 401");
    }

    @Test
    void protectedEndpointRejectsForgedToken() throws Exception {
        // 伪造 token：JwtUtils.validateJwtToken 捕获 MalformedJwtException 后返回 false，
        // filter 不设置认证上下文，最终由认证入口点返回 401
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("forged.jwt.token.payload");
        ResponseEntity<String> response = rest.exchange("/account/v1/info", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), "伪造 token 访问受保护接口应返回 HTTP 401");
    }

    @Test
    void normalAccountCannotAccessProtectedEndpoint() throws Exception {
        // webadmin=0 门禁验证：普通账号可以登录（AuthService.getToken 不校验 webadmin），
        // 但 UserDetailsServiceImpl.loadUserByUsername 对非 webadmin 账号返回 null，
        // AuthTokenFilter 中 userDetails.getAuthorities() 抛 NPE 被 filter 的 catch 块吞掉并中断过滤链。
        // 现状形态：HTTP 200 + 空响应体（未显式 sendError）。若未来修复为显式 401/403，本用例会失败，
        // 请同步更新预期为 UNAUTHORIZED——这正是门禁回归的哨兵断言。
        AccountsDO user = createAccount("usr", "userPass1", false);
        String token = login(user.getName(), "userPass1");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange("/account/v1/info", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "webadmin=0 门禁现状为 filter 吞 NPE 后返回空 200；若未来改为显式拒绝请同步更新本断言：" + response);
        String body = response.getBody();
        boolean returnedSuccess = false;
        if (body != null && !body.isBlank()) {
            try {
                returnedSuccess = objectMapper.readTree(body).path("code").asInt() == SUCCESS_CODE;
            } catch (Exception ignored) {
                // 响应不是合法 JSON，同样视为未成功返回账号信息
            }
        }
        assertFalse(returnedSuccess, "普通账号（webadmin=0）不应拿到业务成功码，实际响应体：" + body);
    }

    @Test
    void logoutSucceeds() throws Exception {
        // AuthController.logout 无业务逻辑直接返回 ResultBody.success()，携带 token 调用应返回 SUCCESS_CODE
        AccountsDO gm = createAccount("gm", "authPass9", true);
        String token = login(gm.getName(), "authPass9");

        JsonNode response = deleteJson("/auth/v1/logout", token);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "登出接口应返回成功码：" + response);
    }
}

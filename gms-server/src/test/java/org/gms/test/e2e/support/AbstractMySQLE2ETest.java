package org.gms.test.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gms.client.DefaultDates;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.mapper.AccountsMapper;
import org.gms.manager.ServerManager;
import org.gms.model.dto.SubmitBody;
import org.gms.util.BCrypt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试基类：本机 Docker 拉起 MySQL 8（镜像 tag 固定 mysql:8.0，与生产一致），
 * 真实 Spring 上下文（RANDOM_PORT）+ 真实 Flyway 全量迁移 + 真实 HTTP 调用。
 * <p>
 * 所有 E2E 测试类共享同一个容器实例与同一个 Spring 上下文（配置一致时自动缓存）。
 * 游戏服已通过 {@code gms.service.game-server-enabled=false} 关闭（见 application-test.yml），
 * 禁止在测试中调用 {@code /server/v1/*} 生命周期接口或触碰 {@code Server.getInstance()}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractMySQLE2ETest {

    /** 业务成功码，见 BizExceptionEnum.SUCCESS。 */
    protected static final int SUCCESS_CODE = 20000;

    /**
     * 静态桥接快照：首个 E2E 测试类加载时（早于其 Spring 上下文创建）保存
     * {@code ServerManager.applicationContext} 的既有值。全量跑时为纯单元测试经
     * {@code BotTestSupport} 注入的 mock 容器，纯 E2E 跑时为 null。每个用例结束后
     * （见 {@link #restoreServerManagerContext()}）还原该值，避免 E2E 上下文启动时
     * Spring 对 {@code ApplicationContextAware} 桥接的覆写污染后续纯单元测试。
     */
    private static final ApplicationContext PRE_CONTEXT = ServerManager.getApplicationContext();

    /**
     * 共享 MySQL 容器：故意<b>不加</b> {@code @Container} 注解，而由 {@link #startMysqlOnce()} 在 JVM 内懒启动一次。
     * <p>
     * 原因：Testcontainers 1.21.0 的 JUnit5 扩展会把每个 {@code @Container} 静态字段包装成
     * {@code ExtensionContext.Store.CloseableResource}，在<b>每个测试类结束</b>时调用 {@code container.stop()}
     * （实测类结束边界容器被 SIGKILL、下一类再拉起新容器、端口随机漂移）；而 Spring 上下文缓存 key
     * 不含 {@code @DynamicPropertySource} 注入的端口值，后续测试类会复用指向已死容器旧端口的上下文，
     * Druid 无限重连导致全量测试挂死。手动管理生命周期后，容器随 JVM 退出由 Ryuk 统一回收。
     */
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("beidou")
            .withUsername("root")
            .withPassword("root")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    /** 整个测试 JVM 内只启动一次容器；重复调用（跨测试类）直接复用已运行实例。 */
    private static synchronized void startMysqlOnce() {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        // 必须先确保容器已启动，getJdbcUrl() 依赖映射端口；懒启动保证 Docker 可用性条件通过后才拉起容器
        startMysqlOnce();
        String jdbcUrl = MYSQL.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        registry.add("mybatis-flex.datasource.mysql.url",
                () -> jdbcUrl + separator + "createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8");
        registry.add("mybatis-flex.datasource.mysql.username", MYSQL::getUsername);
        registry.add("mybatis-flex.datasource.mysql.password", MYSQL::getPassword);
    }

    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected AccountsMapper accountsMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected ApplicationContext applicationContext;

    // ---------- 静态桥接保护 ----------

    /**
     * 每个用例执行前，把 {@code ServerManager.applicationContext} 桥接指回本 E2E 的真实
     * Spring 容器。E2E 上下文在首次创建时 Spring 会调用 {@code ServerManager.setApplicationContext}
     * 写入真实容器，但后续用例前可能已被 {@link #restoreServerManagerContext()} 还原为
     * mock/null（跨测试类隔离需要），因此这里幂等地重新绑定。
     */
    @BeforeEach
    void bindRealApplicationContext() {
        applicationContext.getBean(ServerManager.class).setApplicationContext(applicationContext);
    }

    /**
     * 每个用例结束后，把桥接还原为 E2E 启动前的快照值（{@link #PRE_CONTEXT}）：
     * 全量跑时还原为 {@code BotTestSupport} 注入的 mock 容器，保证排在 E2E 之后执行的
     * 纯单元测试仍按原设计拿到 mock 上下文（否则 {@code Mockito.when(ctx.getBean(...))}
     * 会因拿到真实 bean 抛 MissingMethodInvocation）。
     * 快照为 null（纯 E2E 新 JVM，无既有桥接值）时保持不动——setApplicationContext 带
     * @NonNull 校验拒绝 null，且此时没有旧单测需要还原；后续用例由
     * {@link #bindRealApplicationContext()} 幂等重绑真实容器。
     */
    @AfterEach
    void restoreServerManagerContext() {
        if (PRE_CONTEXT != null) {
            applicationContext.getBean(ServerManager.class).setApplicationContext(PRE_CONTEXT);
        }
    }

    // ---------- 账号与认证辅助 ----------

    /** createAccount/trackAccount 登记的账号名，@AfterEach 统一清理，防止共享库持续膨胀。 */
    private final List<String> trackedAccountNames = new ArrayList<>();

    /**
     * 生成不超过 13 字符（accounts.name 上限）的唯一账号名。
     * 前缀请控制在 4 个字符以内，剩余长度由 UUID 保证唯一。
     */
    protected String uniqueName(String prefix) {
        assertTrue(prefix != null && prefix.length() <= 4, "uniqueName 前缀必须非空且不超过 4 字符：" + prefix);
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 直接在库中造一个账号（绕过注册接口，可指定 webadmin 权限）。
     * 密码使用与生产一致的 SHA-512 十六进制哈希（BCrypt.hashpwSHA512）。
     * 账号名自动登记，@AfterEach 统一清理。
     */
    protected AccountsDO createAccount(String prefix, String password, boolean webadmin) throws NoSuchAlgorithmException {
        AccountsDO account = AccountsDO.builder()
                .name(uniqueName(prefix))
                .password(BCrypt.hashpwSHA512(password))
                .webadmin(webadmin ? 1 : 0)
                .language(3)
                .birthday(Date.valueOf(DefaultDates.getBirthday()))
                .tempban(Timestamp.valueOf(DefaultDates.getTempban()))
                .build();
        accountsMapper.insertSelective(account);
        trackedAccountNames.add(account.getName());
        return account;
    }

    /** 登记一个外部创建（如 HTTP 注册接口）的账号名，由 @AfterEach 统一清理。 */
    protected void trackAccount(String name) {
        trackedAccountNames.add(name);
    }

    @AfterEach
    void cleanupTrackedAccounts() {
        if (trackedAccountNames.isEmpty()) {
            return;
        }
        String inClause = trackedAccountNames.stream()
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM accounts WHERE name IN (" + inClause + ")");
        trackedAccountNames.clear();
    }

    /** 登录并返回 JWT token（断言登录成功）。 */
    protected String login(String username, String password) {
        SubmitBody<Map<String, String>> body = new SubmitBody<>();
        body.setRequestId(UUID.randomUUID().toString());
        body.setData(Map.of("username", username, "password", password));
        JsonNode response = postJson("/auth/v1/login", body, null);
        assertEquals(SUCCESS_CODE, response.path("code").asInt(), "登录应成功，响应：" + response);
        String token = response.path("data").path("token").asText();
        assertNotNull(token, "登录响应应包含 token：" + response);
        return token;
    }

    // ---------- HTTP 辅助（真实走 Filter/Security 全链路） ----------

    protected JsonNode postJson(String url, Object body, String token) {
        return exchangeJson(url, HttpMethod.POST, body, token);
    }

    protected JsonNode getJson(String url, String token) {
        return exchangeJson(url, HttpMethod.GET, null, token);
    }

    protected JsonNode putJson(String url, Object body, String token) {
        return exchangeJson(url, HttpMethod.PUT, body, token);
    }

    protected JsonNode deleteJson(String url, String token) {
        return exchangeJson(url, HttpMethod.DELETE, null, token);
    }

    protected JsonNode exchangeJson(String url, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }
}

package org.gms.test.e2e;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.dao.entity.GameConfigDO;
import org.gms.dao.mapper.CharactersMapper;
import org.gms.dao.mapper.GameConfigMapper;
import org.gms.test.e2e.support.AbstractMySQLE2ETest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Flex 持久层（Mapper/实体）对真实 MySQL 的端到端 CRUD 验证。
 * <p>
 * 继承 {@link AbstractMySQLE2ETest}，共享 Testcontainers MySQL 8 容器、真实 Spring 上下文
 * 与 Flyway 全量迁移结果；游戏服已禁用，本类只通过 Mapper/JdbcTemplate 直连数据库，
 * 不调用任何 REST 接口、不触碰游戏服状态。
 * <p>
 * 所有造数均使用唯一前缀，{@link #cleanupCreatedData()} 在每条用例结束后自清，
 * 保证共享库中不残留本类数据。
 */
class MapperDaoE2ETest extends AbstractMySQLE2ETest {

    @Autowired
    private CharactersMapper charactersMapper;
    @Autowired
    private GameConfigMapper gameConfigMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本用例创建的账号名前缀，@AfterEach 按 name LIKE 前缀清理。 */
    private final List<String> createdNamePrefixes = new ArrayList<>();
    /** 本用例创建的账号 id，@AfterEach 先清其角色再清账号。 */
    private final List<Integer> createdAccountIds = new ArrayList<>();
    /** 本用例创建的 game_config 行 config_code，@AfterEach 按 code 兜底清理。 */
    private final List<String> createdConfigCodes = new ArrayList<>();

    @AfterEach
    void cleanupCreatedData() {
        // 先删角色，再删账号，避免残留角色引用账号
        for (Integer accountId : createdAccountIds) {
            jdbcTemplate.update("DELETE FROM characters WHERE accountid = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        for (String prefix : createdNamePrefixes) {
            jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE ?", prefix + "%");
        }
        for (String configCode : createdConfigCodes) {
            jdbcTemplate.update("DELETE FROM game_config WHERE config_code = ?", configCode);
        }
    }

    /**
     * Accounts CRUD 回环：insertSelective → selectOneByName → update（忽略 null 字段）→ deleteById。
     */
    @Test
    void accountsCrudRoundTrip() {
        String name = uniqueName("crud");
        String password = "e2e-crud-pass";
        createdNamePrefixes.add("crud");

        // 插入：只给 name/password/language，其余 NOT NULL 列全部依赖 DDL 默认值
        AccountsDO insert = AccountsDO.builder()
                .name(name)
                .password(password)
                .language(3)
                .build();
        assertEquals(1, accountsMapper.insertSelective(insert), "insertSelective 应插入 1 行");

        // 按 name 查回，校验关键字段一致
        AccountsDO found = accountsMapper.selectOneByName(name);
        assertNotNull(found, "selectOneByName 应能查到刚插入的账号");
        assertNotNull(found.getId(), "自增主键应存在");
        assertEquals(name, found.getName(), "name 应与插入值一致");
        assertEquals(password, found.getPassword(), "password 应与插入值一致");
        assertEquals(3, found.getLanguage(), "language 应与插入值一致");

        // 构造仅含 id + 新 email 的实体调用 update：
        // MyBatis-Flex 的 BaseMapper.update(entity) 默认忽略 null 字段（等价 update(entity, true)），
        // 因此这里只会更新 email，不会把其他字段刷成 null
        String newEmail = "e2e-crud@test.local";
        assertEquals(1, accountsMapper.update(AccountsDO.builder()
                .id(found.getId())
                .email(newEmail)
                .build()), "update 应命中 1 行");

        // 重查断言 email 已更新、未传入字段不受影响
        AccountsDO afterUpdate = accountsMapper.selectOneByName(name);
        assertNotNull(afterUpdate, "更新后 selectOneByName 仍应查到账号");
        assertEquals(newEmail, afterUpdate.getEmail(), "email 应被选择性更新");
        assertEquals(name, afterUpdate.getName(), "未传入的字段不应被改动");

        // 删除并确认按主键查不到
        assertEquals(1, accountsMapper.deleteById(found.getId()), "deleteById 应删除 1 行");
        assertNull(accountsMapper.selectOneById(found.getId()), "deleteById 后按主键应查不到");
    }

    /**
     * 分页查询：paginateWithRelations 的总行数、页大小、翻页与 name like 过滤。
     */
    @Test
    void accountsPaginationWithRelations() {
        // 用 4 位随机十六进制前缀，确保 like 过滤恰好命中本用例的 5 个账号，不受共享库中其他数据干扰
        String prefix = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 3);
        createdNamePrefixes.add(prefix);
        for (int i = 0; i < 5; i++) {
            accountsMapper.insertSelective(AccountsDO.builder()
                    .name(uniqueName(prefix))
                    .password("e2e-page-pass")
                    .language(3)
                    .build());
        }

        // 空条件的第 1 页：总行数至少 5（库里还含迁移脚本的种子数据），本页恰好 2 条
        Page<AccountsDO> page1 = accountsMapper.paginateWithRelations(1, 2, new QueryWrapper());
        assertTrue(page1.getTotalRow() >= 5, "账号总数应不少于本用例造的 5 条");
        assertEquals(2, page1.getRecords().size(), "第 1 页应恰好返回 2 条");

        // 翻到第 3 页（size=2）：总行数 >= 5 时第 3 页必然非空
        Page<AccountsDO> page3 = accountsMapper.paginateWithRelations(3, 2, new QueryWrapper());
        assertFalse(page3.getRecords().isEmpty(), "第 3 页不应为空");

        // 带 name like 前缀过滤：恰好命中本用例 5 条
        Page<AccountsDO> filtered = accountsMapper.paginateWithRelations(1, 10,
                new QueryWrapper().like(AccountsDO::getName, prefix));
        assertEquals(5L, filtered.getTotalRow(), "like 前缀过滤应恰好命中 5 条");
    }

    /**
     * 唯一约束冲突：accounts.name 有 UNIQUE KEY，重复 name 的插入必须失败。
     */
    @Test
    void duplicateAccountNameRejectedByUniqueIndex() {
        String name = uniqueName("dup");
        createdNamePrefixes.add("dup");
        accountsMapper.insertSelective(AccountsDO.builder()
                .name(name)
                .password("e2e-dup-pass")
                .language(3)
                .build());

        // Mapper 路径：MyBatis-Flex/MyBatis-Spring 可能把底层的 SQLIntegrityConstraintViolationException
        // 翻译成 DuplicateKeyException，也可能保留为其他运行时异常，这里保守断言"抛运行时异常"，
        // 排除断言自身的 AssertionError 等意外通过
        AccountsDO duplicate = AccountsDO.builder()
                .name(name)
                .password("e2e-dup-pass2")
                .language(3)
                .build();
        assertThrows(RuntimeException.class, () -> accountsMapper.insertSelective(duplicate),
                "重复 name 违反唯一索引，insertSelective 应抛运行时异常");

        // JDBC 路径：JdbcTemplate 走 Spring 的 SQLExceptionTranslator，异常类型确定为 DuplicateKeyException
        assertThrows(DuplicateKeyException.class,
                () -> jdbcTemplate.update("INSERT INTO accounts (name, password) VALUES (?, ?)", name, "e2e-dup-pass3"),
                "重复 name 的 JDBC 插入应抛 DuplicateKeyException");
    }

    /**
     * 事务回滚：TransactionTemplate 内插入可见，setRollbackOnly 后事务外不可见。
     */
    @Test
    void transactionRollbackLeavesNoTrace() {
        String name = uniqueName("txn");
        createdNamePrefixes.add("txn");
        AccountsDO account = AccountsDO.builder()
                .name(name)
                .password("e2e-txn-pass")
                .language(3)
                .build();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            accountsMapper.insertSelective(account);
            assertNotNull(accountsMapper.selectOneByName(name), "同一事务内应能查到刚插入的账号");
            status.setRollbackOnly();
            return null;
        });

        assertNull(accountsMapper.selectOneByName(name), "事务回滚后账号不应落库");
    }

    /**
     * GameConfigDO 读写：game_config 表 NOT NULL 列为 config_type/config_code/config_value，
     * insertSelective 插入 type="e2emapper" 的配置行，按 config_code 查回后 deleteById 清理。
     */
    @Test
    void gameConfigRowRoundTrip() {
        String configCode = uniqueName("cfg");
        createdConfigCodes.add(configCode);
        GameConfigDO config = GameConfigDO.builder()
                .configType("e2emapper")
                .configSubType("0")
                .configClazz("java.lang.String")
                .configCode(configCode)
                .configValue("e2e-config-value")
                .configDesc("E2E 映射层测试临时配置行")
                .build();
        assertEquals(1, gameConfigMapper.insertSelective(config), "insertSelective 应插入 1 行配置");

        // 按 config_code 精确查回（id 从查询结果取，不依赖 insertSelective 是否回填主键）
        GameConfigDO found = gameConfigMapper.selectOneByQuery(
                new QueryWrapper().eq(GameConfigDO::getConfigCode, configCode));
        assertNotNull(found, "selectOneByQuery 应能查到刚插入的配置行");
        assertEquals("e2emapper", found.getConfigType(), "configType 应与插入值一致");
        assertEquals("e2e-config-value", found.getConfigValue(), "configValue 应与插入值一致");

        // deleteById 清理（@AfterEach 按 config_code 兜底）
        assertEquals(1, gameConfigMapper.deleteById(found.getId()), "deleteById 应删除 1 行");
        assertNull(gameConfigMapper.selectOneByQuery(
                new QueryWrapper().eq(GameConfigDO::getConfigCode, configCode)), "删除后应查不到该配置行");
    }

    /**
     * CharactersDO 最小行：characters 表所有 NOT NULL 列均有 DDL 默认值（无"必填且无默认"的列），
     * 因此最小行只需 accountid + name（另显式给 world/level 增加可读性）。
     * 表无外键约束，但 @AfterEach 仍按"先删角色、再删账号"的顺序清理。
     */
    @Test
    void charactersMinimalRowRoundTrip() throws NoSuchAlgorithmException {
        AccountsDO account = createAccount("chr", "e2eCharPass1", false);
        createdAccountIds.add(account.getId());

        CharactersDO character = CharactersDO.builder()
                .accountid(account.getId())
                .world(0)
                .name(uniqueName("chr"))
                .level(1)
                .build();
        assertEquals(1, charactersMapper.insertSelective(character), "insertSelective 应插入 1 个角色");

        // 按 accountid 查回该账号下的角色
        List<CharactersDO> characters = charactersMapper.selectListByQuery(
                new QueryWrapper().eq(CharactersDO::getAccountid, account.getId()));
        assertFalse(characters.isEmpty(), "按 accountid 应能查到刚插入的角色");
        CharactersDO found = characters.stream()
                .filter(item -> character.getName().equals(item.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "查回列表应包含刚插入的角色");

        // 删除角色（账号由 @AfterEach 清理）
        assertEquals(1, charactersMapper.deleteById(found.getId()), "deleteById 应删除 1 个角色");
        assertNull(charactersMapper.selectOneById(found.getId()), "删除后按主键应查不到");
    }
}

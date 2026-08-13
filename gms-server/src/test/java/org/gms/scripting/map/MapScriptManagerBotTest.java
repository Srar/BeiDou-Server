package org.gms.scripting.map;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * {@link MapScriptManager#runMapScript(Client, String, boolean)} 对 headless botClient 的防御：
 * c == null 或 c.getPlayer() == null 时直接返回 false——否则空图首个 bot 进入会在
 * chr.getMapId() 处 NPE。脚本不存在路径同样安全返回 false。
 * <p>
 * MapScriptManager 静态初始化会创建 graal.js ScriptEngineFactory（classpath 已带），
 * 脚本查找路径会触碰 ServerManager.getApplicationContext() → 需 BotTestSupport 注入 mock 容器。
 */
class MapScriptManagerBotTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void playerlessClientReturnsFalseWithoutThrowing() {
        Client client = Mockito.mock(Client.class);
        when(client.getPlayer()).thenReturn(null);

        assertFalse(MapScriptManager.getInstance().runMapScript(client, "bot_never_exists_xyz", true),
                "headless client (player=null) must be skipped without exception");
    }

    @Test
    void missingScriptForRealPlayerReturnsFalseWithoutThrowing() {
        Client client = Mockito.mock(Client.class);
        Character chr = Mockito.mock(Character.class);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getMapId()).thenReturn(123);

        // firstUser=true 走 enteredScript 记录分支，随后脚本文件不存在 → 安全返回 false
        assertFalse(MapScriptManager.getInstance().runMapScript(client, "bot_never_exists_xyz", true),
                "missing map script must return false without exception");
    }
}

package org.gms.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 中英 i18n 资源对拍护栏：bot 相关 key 在 zh_CN 与 en_US 两侧必须一一对应。
 * 把「靠人肉保证的双语言一致性」固化为 CI 测试——新增 bot key 时只加一边会立刻失败。
 * 零 Spring/DB 依赖（直接读类路径 properties 文件）。
 */
class I18nBotKeysParityTest {

    private static final String[] BOT_KEY_PREFIXES = {
            "bot.", "BotCommand.", "BotSM.", "BotTickService.", "BotTiming.", "BotTypeManager.",
            "BotGeneration.", "BotServerAccess.", "BotMapEntryResponder.", "BotStartupManager.",
            "ShopOfferSystem.", "EquipOmitList.", "GenericEquipPool.", "GachaBot.",
            "DropGameBot.playback.", "HenesysJQBot.playback.", "TutorialBot.playback.",
            "WarpCommands.portalDropDown.", "BotGeneration.spawnChoreography.",
            "MovementCommands.waitGapLong", "MapleMessengerConsole.",
            "OPQCommands.", "FMBot.", "GCMovement."
    };

    private static final String[][] FILE_PAIRS = {
            {"i18n/message_zh_CN.properties", "i18n/message_en_US.properties"},
            {"i18n/log_zh_CN.properties", "i18n/log_en_US.properties"},
            {"i18n/exception_zh_CN.properties", "i18n/exception_en_US.properties"},
    };

    @Test
    void botKeysMatchBetweenLanguages() throws IOException {
        for (String[] pair : FILE_PAIRS) {
            Set<String> zh = botKeys(load(pair[0]));
            Set<String> en = botKeys(load(pair[1]));
            assertEquals(zh, en, "bot key sets must match between " + pair[0] + " and " + pair[1]);
        }
    }

    private static Set<String> botKeys(Properties props) {
        return props.stringPropertyNames().stream()
                .filter(I18nBotKeysParityTest::isBotKey)
                .collect(Collectors.toSet());
    }

    private static boolean isBotKey(String key) {
        for (String prefix : BOT_KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Properties load(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream is = I18nBotKeysParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("missing classpath resource: " + resource);
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        return props;
    }
}

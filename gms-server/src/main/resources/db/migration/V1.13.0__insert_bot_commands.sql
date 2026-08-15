SET NAMES utf8mb4;

-- ─────────────────────────────────────────────
-- GM 命令注册：SoloMapling Bot 测试子命令（gm4）
-- ─────────────────────────────────────────────
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'move', 4, 1, 'BotMoveCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'move'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'test', 4, 1, 'TestDevCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'test'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'tradebot', 4, 1, 'TradeBotTestCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'tradebot'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'reactor', 4, 1, 'ReactorCommands', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'reactor'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'fmbot', 4, 1, 'FMBotCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'fmbot'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'env', 4, 1, 'EnvironmentCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'env'
);

-- ─────────────────────────────────────────────
-- GM 命令中英文说明（lang_resources）
-- ─────────────────────────────────────────────
-- 中文
INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'move', 'Bot 移动/路径/回放测试命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'move'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'test', 'Test Dev 测试命令（reactor/信使/flavor/事件等）', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'test'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'tradebot', '交易 Bot 测试命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'tradebot'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'reactor', 'Reactor 检查与 bot 攻击测试命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'reactor'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'fmbot', 'FM Bot 测试命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'fmbot'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'env', '环境生成与批量 spawn 命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'env'
);

-- 英文
INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'move', 'Bot movement, pathfinding and replay test commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'move'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'test', 'Test dev commands (reactor, messenger, flavor, events, etc.)', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'test'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'tradebot', 'Trade bot test commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'tradebot'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'reactor', 'Reactor inspection and bot-attack test commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'reactor'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'fmbot', 'FM bot test commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'fmbot'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'env', 'Environment generation and bulk spawn commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'env'
);

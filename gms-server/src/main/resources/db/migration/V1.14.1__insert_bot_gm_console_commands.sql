SET NAMES utf8mb4;

-- ─────────────────────────────────────────────
-- GM 控制面命令注册：!opq / !gcmove / !betafmshop（gm4）
-- MMC 是信使命令，不经 command_info，走 MessengerHandler 路由到 MapleMessengerConsole
-- ─────────────────────────────────────────────
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'opq', 4, 1, 'OPQCommands', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'opq'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'gcmove', 4, 1, 'GCMoveCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'gcmove'
);

INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'betafmshop', 4, 1, 'ArtificialFreeMarketCommand', 4
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'betafmshop'
);

-- ─────────────────────────────────────────────
-- GM 命令中英文说明（lang_resources）
-- ─────────────────────────────────────────────
-- 中文
INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'opq', 'OPQ bot 开发命令（状态机分段调试）', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'opq'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'gcmove', 'GC 动态移动（GCMoveSystem）测试/控制命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'gcmove'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'zh-CN', 'command', 'betafmshop', '人工自由市场（FM）商店测试命令', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'zh-CN' AND `lang_code` = 'betafmshop'
);

-- 英文
INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'opq', 'OPQ bot dev commands (piece-by-piece state machine testing)', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'opq'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'gcmove', 'GreenCat dynamic movement (GCMoveSystem) test/control commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'gcmove'
);

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`, `lang_extend`)
SELECT 'en-US', 'command', 'betafmshop', 'Artificial free market (FM) shop test commands', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `lang_resources` WHERE `lang_type` = 'en-US' AND `lang_code` = 'betafmshop'
);

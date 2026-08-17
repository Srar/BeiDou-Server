SET NAMES utf8mb4;

-- ─────────────────────────────────────────────
-- 客户端崩溃诊断：包记录开关配置（game_config）
-- ─────────────────────────────────────────────
INSERT INTO `game_config`(`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`, `update_time`)
SELECT 'server', 'Debug', 'java.lang.Boolean', 'packet_trace_enabled', 'true',
       '客户端崩溃诊断：每连接收发包环形记录开关（Crash diagnosis: per-connection packet trace switch）', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `game_config` WHERE `config_code` = 'packet_trace_enabled'
);

INSERT INTO `game_config`(`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`, `update_time`)
SELECT 'server', 'Debug', 'java.lang.Integer', 'packet_trace_capacity', '2048',
       '客户端崩溃诊断：每连接环形缓冲容量（包数），默认 2048（约 2 分钟窗口）（Crash diagnosis: per-connection ring buffer capacity in packets, default 2048, about 2 minutes）', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `game_config` WHERE `config_code` = 'packet_trace_capacity'
);

INSERT INTO `game_config`(`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`, `update_time`)
SELECT 'server', 'Debug', 'java.lang.Boolean', 'packet_trace_include_move', 'false',
       '客户端崩溃诊断：记录高频移动包，默认关闭防缓冲被刷掉（Crash diagnosis: trace high-frequency move packets, disabled by default to avoid flushing the buffer）', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `game_config` WHERE `config_code` = 'packet_trace_include_move'
);

-- ─────────────────────────────────────────────
-- GM 命令注册：!pktrace（gm3）
-- ─────────────────────────────────────────────
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
SELECT 'pktrace', 3, 1, 'PacketTraceCommand', 3
WHERE NOT EXISTS (
    SELECT 1 FROM `command_info` WHERE `syntax` = 'pktrace'
);

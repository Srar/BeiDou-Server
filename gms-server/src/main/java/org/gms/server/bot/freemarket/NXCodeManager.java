package org.gms.server.bot.freemarket;

import lombok.extern.slf4j.Slf4j;
import org.gms.util.DatabaseConnection;
import org.gms.util.Randomizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * NX 礼品码生成（逐行移植自 SoloMapling server.NXCodeManager）。
 * nxcode / nxcode_items 表已由 Flyway V1.0.28 落地（id 为 AUTO_INCREMENT），
 * createCompleteNXCode 已恢复真实 DB 写入。
 */
@Slf4j
public final class NXCodeManager {

    /**
     * 大过期时间（毫秒时间戳，约 2252 年）。
     * 与 CouponCodeHandler（expiration &lt; Server.getCurrentTime() 判过期）及
     * NxCodeService.clearExpirations（System.currentTimeMillis() - 14 天清理）同为毫秒单位，
     * 保证码不会立即过期或被清理。
     */
    private static final long EXPIRATION_MILLIS = 8901234567890L;

    private NXCodeManager() {
    }

    /**
     * Generates a random gift card code in the format: XXXXXX-XXXXXX-XXXXXX
     * Uses only unambiguous characters (excludes 0, O, I, 1, L etc.)
     *
     * @return String of 17 characters total (15 alphanumeric + 2 dashes)
     */
    public static String generateGiftCardCode() {
        String characters = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
        StringBuilder codeBuilder = new StringBuilder();

        for (int section = 0; section < 3; section++) {
            if (section > 0) {
                codeBuilder.append("-");
            }
            for (int i = 0; i < 5; i++) {
                int randomIndex = Randomizer.nextInt(characters.length());
                codeBuilder.append(characters.charAt(randomIndex));
            }
        }
        return codeBuilder.toString();
    }

    /**
     * Overloaded convenience method with default 10k NX quantity.
     */
    public static int createCompleteNXCode(String code) {
        return createCompleteNXCode(code, 10000);
    }

    /**
     * Convenience method to create both nxcode and nxcode_items entries in one call.
     * 表已落地（V1.0.28），直接 JDBC 写入：nxcode 用 RETURN_GENERATED_KEYS 取自增 id，
     * nxcode_items 显式 type=4 = nxCredit（见 CouponCodeHandler case 4，DB 默认 5 为物品分支）、item=0、quantity=面额。
     * 两表插入在同一事务中，失败回滚并返回 0。
     *
     * @param code     The code string
     * @param quantity The quantity of NX (default 10000 for 10k NX)
     * @return 1 on success, 0 on failure
     */
    public static int createCompleteNXCode(String code, int quantity) {
        code = code.replace("-", "");

        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                final int codeId;
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO nxcode (code, retriever, expiration) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, code);
                    ps.setNull(2, Types.VARCHAR); // completely empty/null
                    ps.setLong(3, EXPIRATION_MILLIS); // big expiration time

                    ps.executeUpdate();

                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            throw new SQLException("Failed to retrieve generated nxcode id");
                        }
                        codeId = rs.getInt(1);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO nxcode_items (codeid, type, item, quantity) VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, codeId);
                    ps.setInt(2, 4); // type 4 = nxCredit（见 CouponCodeHandler case 4）
                    ps.setInt(3, 0); // item id (not used for NX)
                    ps.setInt(4, quantity);

                    ps.executeUpdate();
                }

                con.commit();
                return 1;
            } catch (SQLException e) {
                try {
                    con.rollback();
                } catch (SQLException rollbackEx) {
                    log.warn("[NXCodeManager] Rollback failed for code: {}", code, rollbackEx);
                }
                log.error("[NXCodeManager] Failed to persist NX code: {}", code, e);
                return 0;
            }
        } catch (SQLException e) {
            log.error("[NXCodeManager] Failed to open DB connection for NX code: {}", code, e);
            return 0;
        }
    }
}

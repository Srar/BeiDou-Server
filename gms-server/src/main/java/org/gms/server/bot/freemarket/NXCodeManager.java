package org.gms.server.bot.freemarket;

import lombok.extern.slf4j.Slf4j;
import org.gms.util.Randomizer;

/**
 * NX 礼品码生成（逐行移植自 SoloMapling server.NXCodeManager）。
 * gms 底座差异：未落地 nxcode / nxcode_items 表，createCompleteNXCode 降级为日志记录。
 */
@Slf4j
public final class NXCodeManager {

    private NXCodeManager() {
    }

    /**
     * Generates a random gift card code in the format: XXXXXX-XXXXXX-XXXXXX
     * Uses only unambiguous characters (excludes 0, O, I, 1, L etc.)
     *
     * @return String of 20 characters total (18 alphanumeric + 2 dashes)
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
     * gms 移植：源写 DB 表；此处降级为日志（TODO：nxcode 表落地后恢复 DB 写入）。
     */
    public static int createCompleteNXCode(String code, int quantity) {
        code = code.replace("-", "");
        log.info("[NXCodeManager] Generated NX code: {} (quantity {})", code, quantity);
        return 1;
    }
}

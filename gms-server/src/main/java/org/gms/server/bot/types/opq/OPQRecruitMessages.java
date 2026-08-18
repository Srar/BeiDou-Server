package org.gms.server.bot.types.opq;

import org.gms.client.Character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Randomized OPQ lobby recruit-chat generator（国服化版）.
 *
 * Mirrors the structure of MerchantBot.MerchantBotMessageCreator:
 *   [prefix] [pq name] [optional level tag] [optional filler]
 *
 * The goal is visual noise variety, not believability — these lines appear in
 * the Orbis PQ lobby crowd, interleaved with real-player chat.
 * 国服化：前缀/队名/填充全部中文口语（组队任务来人/缺人/求组），
 * 自报等级只带级数不带英文职业名，去掉 @@@@ 与 15% 大写。
 */
public final class OPQRecruitMessages {

    private OPQRecruitMessages() {}

    private static final List<String> PREFIXES = new ArrayList<>(Arrays.asList(
            "组队任务来人", "OPQ 缺人", "OPQ 来", "求组 OPQ", "有人组队任务吗", "OPQ 速来"
    ));

    private static final List<String> PQ_NAMES = new ArrayList<>(Arrays.asList(
            "天空组队任务", "OPQ", "天空之城任务", "组队任务", "天空塔任务"
    ));

    private static final List<String> FILLERS = new ArrayList<>(Arrays.asList(
            "!!", "速度", "在线等", "缺2", "来人啦", "++++++++"
    ));

    public static String generateRecruitMessage(Character chr) {
        Random random = new Random();

        String prefix = PREFIXES.get(random.nextInt(PREFIXES.size()));
        String pqName = PQ_NAMES.get(random.nextInt(PQ_NAMES.size()));

        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(' ');

        // 35% chance to self-tag with level ("55级 OPQ 来人" style).
        if (chr != null && random.nextDouble() < 0.35) {
            try {
                sb.insert(0, chr.getLevel() + "级 ");
            } catch (Exception ignored) {
                // Character APIs missing something; skip the self-tag.
            }
        }

        sb.append(pqName);

        // 0–2 filler appends
        int fillerCount = random.nextInt(3);
        for (int i = 0; i < fillerCount; i++) {
            sb.append(' ').append(FILLERS.get(random.nextInt(FILLERS.size())));
        }

        return sb.toString().replaceAll("\\[", "").replaceAll("]", "");
    }
}

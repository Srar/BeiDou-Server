package org.gms.server.bot.dialogue;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot 对话句柄（对应 SoloMapling BotDialogueHandler 1:1 移植）。
 * 从 BotDialoguePack 的 YAML 节点加载台词、按节点/上下文/替换串播放。
 */
@Slf4j
public class BotDialogueHandler {

    /**
     * YAML 根节点静态缓存（按 classpath 路径键）：classpath 资源运行期不变，
     * 首次加载后复用，避免每 tick 概率重读 + 重解析（转换风暴期的高频解析开销）。
     * 读取方只读不写，跨线程共享安全。
     */
    private static final Map<String, Map<String, Object>> YAML_ROOT_CACHE = new ConcurrentHashMap<>();

    /** 加载失败/资源缺失哨兵：缓存后不再每 tick 重试，告警只打一次。 */
    private static final Map<String, Object> YAML_MISSING = Collections.emptyMap();

    private Character chr;

    public static class DialogueConstructor {
        private List<String> dialogue;
        private final List<Integer> emotes;             // 节点级表情调色板（随机取）
        private final List<List<Integer>> lineEmotes;   // 按行覆盖，与 dialogue 对齐；null 项 = 用调色板
        private final long duration;

        public DialogueConstructor(List<String> dialogue, List<Integer> emotes, long duration) {
            this(dialogue, emotes, null, duration);
        }

        public DialogueConstructor(List<String> dialogue, List<Integer> emotes, List<List<Integer>> lineEmotes, long duration) {
            this.dialogue = dialogue;
            this.emotes = emotes;
            this.lineEmotes = lineEmotes;
            this.duration = duration;
        }

        private void setDialogue(List<String> newDialogue) {
            this.dialogue = newDialogue;
        }

        public List<String> getDialogue() {
            return this.dialogue;
        }

        public String getDialogue(int i) {
            return getDialogue().get(i);
        }

        /** 从节点级调色板随机取一个表情。与选中哪行无关。 */
        public Integer getEmote() {
            if (emotes == null || emotes.isEmpty()) {
                return 0;
            }
            return emotes.get(Randomizer.nextInt(emotes.size()));
        }

        /** 绑定某行的表情：其标记覆盖（列表则随机取），该行无覆盖时回退节点调色板。 */
        public Integer getEmoteForIndex(int i) {
            if (lineEmotes != null && i >= 0 && i < lineEmotes.size()) {
                List<Integer> override = lineEmotes.get(i);
                if (override != null && !override.isEmpty()) {
                    return override.get(Randomizer.nextInt(override.size()));
                }
            }
            return getEmote();
        }

        public List<Integer> getEmotes() {
            return emotes;
        }

        private long getDuration() {
            return this.duration;
        }
    }

    public BotDialogueHandler(Character chr) {
        this.chr = chr;
    }

    public void executeBotFlavorDialogue(String DialogueNodeName, BotSM botSM) {
        runBotFlavorDialogue(botSM.getChr(), getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName));
    }

    /**
     * 类似 flavor 对话，但台词可含 {TOKEN} 占位符，从 bot 实时游戏状态解析（见
     * DialogueContextResolver）。无法解析 token 的行被跳过，回退到无 token 行，
     * 保证原始 token 绝不进聊天。
     */
    public void executeBotContextDialogue(String DialogueNodeName, BotSM botSM) {
        runBotContextFlavorDialogue(botSM.getChr(), getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName));
    }

    /** 同上，但台词还可经 {PLAYER_*} token 引用一个玩家（等级、职业、声望、装备、宠物、公会……）。 */
    public void executeBotContextDialogue(String DialogueNodeName, BotSM botSM, Character player) {
        runBotContextFlavorDialogue(botSM.getChr(), getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName), player);
    }

    /** 同上，但偏向行选择：上下文（含 {TOKEN}）行只以 contextChance 概率抽取。 */
    public void executeBotContextDialogue(String DialogueNodeName, BotSM botSM, Character player, double contextChance) {
        runBotContextFlavorDialogue(botSM.getChr(), getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName), player, contextChance);
    }

    public void executeBotDialogueWithReplacementStrings(String DialogueNodeName, Map<String, String> replacements, BotSM botSM) {
        runBotDialogue(botSM.getChr(), getDialogueConWithReplacedStrings(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName, replacements));
    }

    public void executeBotDialogue(String DialogueNodeName, BotSM botSM) {
        runBotDialogue(botSM.getChr(), getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName));
    }

    public void listOptions(Character player, BotSM botSM) {
        // gms 未移植 DiceBot（soloMapling.ArtificialPlayer.BotTypes.DiceBot），
        // 源里的 DiceBot 分支移除，统一走基类 displayCommands。
        // TODO(移植): DiceBot 落地后恢复 instanceof DiceBot -> displayCommands 分支。
        botSM.displayCommands(player);
    }

    public static Map<String, Object> readDialogueYaml(String dialoguePack, String dialogueType, String dialogueNode) {
        String filePath = "BotDialoguePack/" + dialoguePack;

        Map<String, Object> root = YAML_ROOT_CACHE.computeIfAbsent(filePath, BotDialogueHandler::loadDialogueYamlRoot);
        if (root == null || root == YAML_MISSING) {
            return null;
        }
        Map<String, Object> botTypeNode = (Map<String, Object>) root.get(dialogueType);
        if (botTypeNode != null) {
            return (Map<String, Object>) botTypeNode.get(dialogueNode);
        }
        return null;
    }

    /** 首次加载某个 YAML 并解析成根节点；失败缓存 {@link #YAML_MISSING} 哨兵。 */
    private static Map<String, Object> loadDialogueYamlRoot(String filePath) {
        try (InputStream in = BotDialogueHandler.class.getResourceAsStream(filePath)) {
            if (in == null) {
                log.warn(I18nUtil.getLogMessage("BotDialogueHandler.yaml.missing", filePath));
                return YAML_MISSING;
            }
            Map<String, Object> root = (Map<String, Object>) new Yaml().load(in);
            return root == null ? YAML_MISSING : root;
        } catch (Exception e) {
            log.warn(I18nUtil.getLogMessage("BotDialogueHandler.yaml.fail", filePath), e);
            return YAML_MISSING;
        }
    }

    public static DialogueConstructor getDialogueCon(String BotTypeDialoguePath, String BotType, String DialogueNodeName) {
        Map<String, Object> dialogMap = readDialogueYaml(BotTypeDialoguePath, BotType, DialogueNodeName);
        if (dialogMap == null) {
            return null;
        }
        List<String> textList = new ArrayList<>();
        List<List<Integer>> lineEmotes = new ArrayList<>();
        boolean anyLineEmote = parseTextEntries(dialogMap.get("text"), textList, lineEmotes);
        List<Integer> emotes = parseEmotes(dialogMap.get("emote"));
        long duration = (convertToInt(dialogMap.get("wait")) * 1000); // milliseconds
        return new DialogueConstructor(textList, emotes, anyLineEmote ? lineEmotes : null, duration);
    }

    /**
     * 节点的 "text" 条目要么是普通字符串（用节点级表情调色板），要么是
     * {line/text, emote} 映射携带自身目标表情。按索引对齐填充 textOut 与 emotesOut
     * （null emote 项表示「无覆盖，用调色板」）。返回是否有条目提供了覆盖。
     */
    private static boolean parseTextEntries(Object raw, List<String> textOut, List<List<Integer>> emotesOut) {
        boolean any = false;
        if (raw instanceof List) {
            for (Object entry : (List<?>) raw) {
                if (entry instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) entry;
                    Object lineVal = m.containsKey("line") ? m.get("line") : m.get("text");
                    textOut.add(lineVal == null ? "" : lineVal.toString());
                    if (m.get("emote") != null) {
                        emotesOut.add(parseEmotes(m.get("emote")));
                        any = true;
                    } else {
                        emotesOut.add(null);
                    }
                } else {
                    textOut.add(entry == null ? "" : entry.toString());
                    emotesOut.add(null);
                }
            }
        }
        return any;
    }

    public static DialogueConstructor getDialogueConWithReplacedStrings(String BotTypeDialoguePath, String BotType, String DialogueNodeName, Map<String, String> replacements) {
        DialogueConstructor og = getDialogueCon(BotTypeDialoguePath, BotType, DialogueNodeName);
        og.setDialogue(replaceStrings(og.getDialogue(), replacements));
        return og;
    }

    public static List<String> replaceStrings(List<String> inputList, Map<String, String> replacements) {
        if (inputList == null || replacements == null) {
            throw new IllegalArgumentException("Input list and replacements map cannot be null");
        }

        List<String> resultList = new ArrayList<>();
        for (String str : inputList) {
            String modifiedStr = str;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                modifiedStr = modifiedStr.replace(entry.getKey(), entry.getValue());
            }
            resultList.add(modifiedStr);
        }
        return resultList;
    }

    public static String getRandomDialogueLine(BotSM botSM, String DialogueNodeName) {
        DialogueConstructor dialog = getDialogueCon(botSM.getDialoguePath(), botSM.getBotType(), DialogueNodeName);
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = dialog.getDialogue();
        int idx = RecentLineGuard.pickIndex(guardKey(botSM.getChr()), lines.size(), RecentLineGuard.DEFAULT_RECENT, null);
        RecentLineGuard.remember(guardKey(botSM.getChr()), idx, RecentLineGuard.DEFAULT_RECENT);
        return lines.get(idx);
    }

    /**
     * 从节点随机取一行并解析 {TOKEN}（对着说话 bot 与可选玩家）。无法解析的行有界重掷，
     * 再回退到无 token 行——与 runBotContextFlavorDialogue 同一策略。节点缺失/为空或
     * 无可解析时返回 null（调用方保持沉默）。
     */
    public static String getRandomResolvedLine(BotSM botSM, String node) {
        return getRandomResolvedLine(botSM, node, null);
    }

    public static String getRandomResolvedLine(BotSM botSM, String node, Character player) {
        return getRandomResolvedLine(botSM.getDialoguePath(), botSM.getBotType(), node, botSM.getChr(), player);
    }

    public static String getRandomResolvedLine(String dialoguePath, String botType, String node, Character speaker, Character player) {
        DialogueConstructor dialog = getDialogueCon(dialoguePath, botType, node);
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = dialog.getDialogue();
        int n = lines.size();
        int tries = Math.min(CONTEXT_REROLLS, n);
        String key = guardKey(speaker);
        Set<Integer> tried = new HashSet<>();
        for (int attempt = 0; attempt < tries; attempt++) {
            int idx = RecentLineGuard.pickIndex(key, n, RecentLineGuard.DEFAULT_RECENT, tried);
            tried.add(idx);
            String raw = lines.get(idx);
            if (!DialogueContextResolver.hasTokens(raw)) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                return raw;
            }
            Optional<String> filled = DialogueContextResolver.fill(raw, speaker, player);
            if (filled.isPresent()) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                return filled.get();
            }
        }
        for (int i = 0; i < n; i++) {
            if (!DialogueContextResolver.hasTokens(lines.get(i))) {
                RecentLineGuard.remember(key, i, RecentLineGuard.DEFAULT_RECENT);
                return lines.get(i);
            }
        }
        return null;
    }

    public static void runBotDialogue(Character character, DialogueConstructor dialog) {
        if (dialog == null) {
            return;
        }
        runDialogue(character, dialog, dialog.getDialogue(), dialog.getEmote());
    }

    public static void runBotFlavorDialogue(Character character, DialogueConstructor dialog) {
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return;
        }
        String key = guardKey(character);
        int idx = RecentLineGuard.pickIndex(key, dialog.getDialogue().size(), RecentLineGuard.DEFAULT_RECENT, null);
        String randomText = dialog.getDialogue().get(idx);
        RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
        runDialogue(character, dialog, Collections.singletonList(randomText), dialog.getEmoteForIndex(idx));
    }

    private static final int CONTEXT_REROLLS = 6;

    public static void runBotContextFlavorDialogue(Character character, DialogueConstructor dialog) {
        runBotContextFlavorDialogue(character, dialog, null);
    }

    /**
     * 掷一行；若含 {TOKEN}，从 bot 上下文（及给定玩家，若有）解析。无法解析的行有界重掷，
     * 再回退到无 token 行，保证原始 token 绝不泄漏。若无可解析且无无 token 行，bot 保持沉默。
     */
    public static void runBotContextFlavorDialogue(Character character, DialogueConstructor dialog, Character player) {
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return;
        }
        List<String> lines = dialog.getDialogue();
        int n = lines.size();
        int tries = Math.min(CONTEXT_REROLLS, n);
        String key = guardKey(character);
        Set<Integer> tried = new HashSet<>();
        for (int attempt = 0; attempt < tries; attempt++) {
            int idx = RecentLineGuard.pickIndex(key, n, RecentLineGuard.DEFAULT_RECENT, tried);
            tried.add(idx);
            String raw = lines.get(idx);
            if (!DialogueContextResolver.hasTokens(raw)) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(raw), dialog.getEmoteForIndex(idx));
                return;
            }
            Optional<String> filled = DialogueContextResolver.fill(raw, character, player);
            if (filled.isPresent()) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(filled.get()), dialog.getEmoteForIndex(idx));
                return;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!DialogueContextResolver.hasTokens(lines.get(i))) {
                RecentLineGuard.remember(key, i, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(lines.get(i)), dialog.getEmoteForIndex(i));
                return;
            }
        }
    }

    /** 上下文（{TOKEN}）行占闲聊的默认比例（其余为普通行）。 */
    public static final double CONTEXT_LINE_CHANCE = 0.20;

    /**
     * 加权的 runBotContextFlavorDialogue：以 contextChance 概率优先上下文行，否则优先普通行；
     * 首选池无话可说时回退另一池。同样的 token 解析 + 不泄漏策略。
     */
    public static void runBotContextFlavorDialogue(Character character, DialogueConstructor dialog, Character player, double contextChance) {
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return;
        }
        List<String> lines = dialog.getDialogue();
        List<Integer> contextLines = new ArrayList<>();
        List<Integer> plainLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            (DialogueContextResolver.hasTokens(lines.get(i)) ? contextLines : plainLines).add(i);
        }
        boolean preferContext = Randomizer.nextDouble() < contextChance;
        List<Integer> first = preferContext ? contextLines : plainLines;
        List<Integer> second = preferContext ? plainLines : contextLines;
        if (emitOneFrom(character, dialog, lines, first, player)) {
            return;
        }
        emitOneFrom(character, dialog, lines, second, player);
    }

    /** 有界重掷，从给定索引池说出一行，解析 token。说出返回 true，池空或无可解析返回 false。 */
    private static boolean emitOneFrom(Character character, DialogueConstructor dialog,
                                       List<String> lines, List<Integer> pool, Character player) {
        if (pool.isEmpty()) {
            return false;
        }
        int tries = Math.min(CONTEXT_REROLLS, pool.size());
        String key = guardKey(character);
        Set<Integer> tried = new HashSet<>();
        for (int attempt = 0; attempt < tries; attempt++) {
            int localIdx = RecentLineGuard.pickIndex(key, pool.size(), RecentLineGuard.DEFAULT_RECENT, tried);
            tried.add(localIdx);
            int idx = pool.get(localIdx);
            String raw = lines.get(idx);
            if (!DialogueContextResolver.hasTokens(raw)) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(raw), dialog.getEmoteForIndex(idx));
                return true;
            }
            Optional<String> filled = DialogueContextResolver.fill(raw, character, player);
            if (filled.isPresent()) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(filled.get()), dialog.getEmoteForIndex(idx));
                return true;
            }
        }
        for (int i = 0; i < pool.size(); i++) {
            int idx = pool.get(i);
            if (!DialogueContextResolver.hasTokens(lines.get(idx))) {
                RecentLineGuard.remember(key, idx, RecentLineGuard.DEFAULT_RECENT);
                runDialogue(character, dialog, Collections.singletonList(lines.get(idx)), dialog.getEmoteForIndex(idx));
                return true;
            }
        }
        return false;
    }

    /** RecentLineGuard 记忆 key：每 bot 一个命名空间（跨节点不重复）。 */
    private static String guardKey(Character chr) {
        return "bh:" + (chr != null ? chr.getId() : -1);
    }

    /** 刻意的同步编排（标准情形）：按 YAML 配置的时长阻塞，使 executeBotDialogue* 调用方保持顺序。 */
    private static void runDialogue(Character character, DialogueConstructor dialog, List<String> textToShow, int emote) {
        if (dialog == null) {
            return;
        }
        botDialogue(character, textToShow);
        botEmote(character, emote);
        blockingSleep(dialog.getDuration());
    }

    private static List<Integer> parseEmotes(Object obj) {
        if (obj instanceof List) {
            List<?> rawList = (List<?>) obj;
            List<Integer> emotes = new ArrayList<>();
            for (Object item : rawList) {
                emotes.add(convertToInt(item));
            }
            return emotes;
        }
        return Collections.singletonList(convertToInt(obj));
    }

    private static Integer convertToInt(Object obj) {
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else if (obj instanceof Long) {
            return ((Long) obj).intValue();
        } else if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);
            } catch (NumberFormatException e) {
                // 去同步 stdout（同 System.out.println 问题），改走 slf4j。
                log.warn("[BotDialogueHandler] Error converting String to int: {}", obj);
            }
        }
        return 0;
    }

    // ── 等价实现（gms 无 SocialCommands，内联 PacketCreator） ────────────────

    /** 等价 SocialCommands.BotDialogue：逐行 BotSpeak，行间 5s（源值）。 */
    private static void botDialogue(Character character, List<String> dialogue) {
        for (int i = 0; i < dialogue.size(); i++) {
            botSpeak(character, dialogue.get(i));
            if (i < dialogue.size() - 1) {
                blockingSleep(5000);
            }
        }
    }

    /** 等价 SocialCommands.BotSpeak -> BotFullChat：普通聊天广播。 */
    private static void botSpeak(Character character, String message) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(
                PacketCreator.getChatText(character.getId(), message, character.getWhiteChat(), 0));
    }

    /** 等价 SocialCommands.BotEmote(character, emote)：表情广播。 */
    private static void botEmote(Character character, int emote) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(PacketCreator.facialExpression(character, emote));
    }

    /** 等价 SoloMapling BotHelpers.blockingSleep：刻意阻塞当前线程（数据驱动的编排）。 */
    private static void blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

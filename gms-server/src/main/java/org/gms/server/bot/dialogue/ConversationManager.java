package org.gms.server.bot.dialogue;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.town.TownPresenceConfig;
import org.gms.server.bot.types.SocialBot;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.yaml.snakeyaml.Yaml;

import java.awt.Point;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * 城镇氛围对话管理器（对应 SoloMapling ConversationManager 1:1 移植）。
 * 让 Henesys 四图 + TownPresence.yaml 里每个城镇地图上的 SocialBot 结群演短对话。
 */
@Slf4j
public class ConversationManager {

    private static ConversationManager instance;
    private ScheduledFuture<?> scheduledTask;
    private final Random random = new Random();
    private boolean running = false;

    private static final int MIN_INTERVAL_MS = 20_000;
    private static final int MAX_INTERVAL_MS = 40_000;

    private static final int SMALL_MAP_MIN_INTERVAL_MS = 70_000;
    private static final int SMALL_MAP_MAX_INTERVAL_MS = 100_000;
    private static final int SMALL_MAP_THRESHOLD = 20;

    private final Map<Integer, Long> lastConversationPerMap = new HashMap<>();

    private static final double CLUSTER_RANGE_X = 250;
    private static final int CLUSTER_RANGE_Y = 30;
    private static final int MAX_CLUSTER_SIZE = 4;

    private static final int[] HENESYS_MAP_IDS = {
            100000000,  // Henesys
            100000100,  // Henesys Market
            100000200,  // Henesys Park
            100000102   // Henesys Potion Shop
    };

    private volatile Set<Integer> ambientMapIds = buildAmbientMapIds();

    private static final String DIALOGUE_YAML = "BotDialoguePack/ConversationDialogue.yaml";

    private final Set<Integer> botsInConversation = Collections.synchronizedSet(new HashSet<>());
    private final LinkedList<String> recentScriptIds = new LinkedList<>();
    private static final int RECENT_HISTORY_SIZE = 40;

    private List<ConversationScript> allScripts;

    private ConversationManager() {}

    public static synchronized ConversationManager getInstance() {
        if (instance == null) {
            instance = new ConversationManager();
        }
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        BotExecutors.ensureStarted();
        loadScripts();
        refreshMapScope();
        scheduleNextTick();
        log.info("Started with {} scripts.", allScripts != null ? allScripts.size() : 0);
    }

    public void refreshMapScope() {
        ambientMapIds = buildAmbientMapIds();
        log.info("Map scope: {} maps.", ambientMapIds.size());
    }

    private static Set<Integer> buildAmbientMapIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int id : HENESYS_MAP_IDS) {
            ids.add(id);
        }
        ids.addAll(TownPresenceConfig.allTownMapIds());
        return ids;
    }

    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        botsInConversation.clear();
        log.info("Stopped.");
    }

    public boolean isInConversation(int characterId) {
        return botsInConversation.contains(characterId);
    }

    private void scheduleNextTick() {
        if (!running) return;
        int delay = MIN_INTERVAL_MS + random.nextInt(MAX_INTERVAL_MS - MIN_INTERVAL_MS);
        scheduledTask = TimerManager.getInstance().schedule(() -> {
            try {
                BotExecutors.runAsync(() -> {
                    try {
                        tick();
                    } catch (Exception e) {
                        log.warn("Error during conversation tick", e);
                    }
                    scheduleNextTick();
                });
            } catch (RejectedExecutionException ignored) {
                // 执行器已关停（停机/重启窗口）：静默返回，不再重排，也不打 error。
            }
        }, delay);
    }

    private void tick() {
        if (allScripts == null || allScripts.isEmpty()) return;

        Set<Integer> mapsWithRealPlayers = getMapsWithRealPlayers();
        if (mapsWithRealPlayers.isEmpty()) return;

        long now = System.currentTimeMillis();

        for (int mapId : ambientMapIds) {
            if (!mapsWithRealPlayers.contains(mapId)) continue;
            if (!isMapCooldownReady(mapId, now)) continue;

            List<Character> cluster = findClusterOnMap(mapId);
            if (cluster != null && cluster.size() >= 2) {
                lastConversationPerMap.put(mapId, now);
                startConversation(cluster, null);
                return;
            }
        }
    }

    private boolean isMapCooldownReady(int mapId, long now) {
        Long lastTime = lastConversationPerMap.get(mapId);
        if (lastTime == null) return true;

        int botCount = getAllCharsOnMap(mapId).size();
        long cooldown;
        if (botCount <= SMALL_MAP_THRESHOLD) {
            cooldown = SMALL_MAP_MIN_INTERVAL_MS + random.nextInt(SMALL_MAP_MAX_INTERVAL_MS - SMALL_MAP_MIN_INTERVAL_MS);
        } else {
            cooldown = MIN_INTERVAL_MS + random.nextInt(MAX_INTERVAL_MS - MIN_INTERVAL_MS);
        }

        return (now - lastTime) >= cooldown;
    }

    private Set<Integer> getMapsWithRealPlayers() {
        Set<Integer> maps = new HashSet<>();
        try {
            for (Character chr : Server.getInstance().getChannel(0, 1).getPlayerStorage().getAllCharacters()) {
                if (!BotHelpers.isBot(chr)) {
                    maps.add(chr.getMapId());
                }
            }
        } catch (Exception e) {
            // Channel not ready
        }
        return maps;
    }

    public void triggerOnMap(Character player) {
        if (allScripts == null || allScripts.isEmpty()) {
            loadScripts();
        }
        int mapId = player.getMapId();
        List<Character> cluster = findClusterOnMap(mapId);
        if (cluster == null || cluster.size() < 2) {
            player.yellowMessage("[ConversationManager] 这张图没有 2 个以上的填充 bot，演不了群戏。");
            return;
        }
        startConversation(cluster, player);
    }

    private List<Character> findClusterOnMap(int mapId) {
        try {
            MapleMap map = Server.getInstance().getChannel(0, 1).getMapFactory().getMap(mapId);
            if (map == null) return null;

            List<Character> fillerBots = new ArrayList<>();
            for (Character chr : map.getAllPlayers()) {
                if (!BotHelpers.isBot(chr)) continue;
                BotSM bot = BotStorage.getBotById(chr.getId());
                if (bot == null || !bot.isAvailableForAmbientActions()) continue;
                if (!(bot instanceof SocialBot)) continue;
                if (botsInConversation.contains(chr.getId())) continue;
                fillerBots.add(chr);
            }

            if (fillerBots.size() < 2) return null;

            Collections.shuffle(fillerBots, random);

            for (Character anchor : fillerBots) {
                List<Character> cluster = new ArrayList<>();
                cluster.add(anchor);
                Point anchorPos = anchor.getPosition();

                for (Character other : fillerBots) {
                    if (other.getId() == anchor.getId()) continue;
                    if (botsInConversation.contains(other.getId())) continue;
                    Point otherPos = other.getPosition();
                    if (Math.abs(anchorPos.x - otherPos.x) <= CLUSTER_RANGE_X
                            && Math.abs(anchorPos.y - otherPos.y) <= CLUSTER_RANGE_Y) {
                        cluster.add(other);
                        if (cluster.size() >= MAX_CLUSTER_SIZE) break;
                    }
                }

                if (cluster.size() >= 2) {
                    return cluster;
                }
            }
        } catch (Exception e) {
            // Map not loaded
        }
        return null;
    }

    private void startConversation(List<Character> cluster, Character debugPlayer) {
        int clusterSize = cluster.size();
        ConversationScript script = pickScript(clusterSize);
        if (script == null) {
            if (debugPlayer != null) debugPlayer.yellowMessage("[ConversationManager] 没有匹配 " + clusterSize + " 人的剧本");
            return;
        }

        List<Character> participants = new ArrayList<>(cluster.subList(0, Math.min(clusterSize, script.getParticipantCount())));

        for (Character chr : participants) {
            botsInConversation.add(chr.getId());
        }

        recentScriptIds.add(script.getId());
        if (recentScriptIds.size() > RECENT_HISTORY_SIZE) {
            recentScriptIds.removeFirst();
        }

        log.info("Starting '{}' with {} bots.", script.getId(), participants.size());

        if (debugPlayer != null) {
            debugPlayer.yellowMessage("[ConversationManager] 剧本: '" + script.getId() + "' | 人数: " + participants.size());
            String[] roles = {"A", "B", "C", "D"};
            for (int i = 0; i < participants.size(); i++) {
                Character p = participants.get(i);
                debugPlayer.yellowMessage("  角色 " + roles[i] + ": " + p.getName()
                        + " | 坐标: (" + p.getPosition().x + ", " + p.getPosition().y + ")"
                        + " | 地图: " + p.getMapId());
            }
        }

        BotExecutors.runAsync(() -> {
            try {
                playConversation(participants, script);
            } catch (Exception e) {
                log.warn("Conversation playback error", e);
            } finally {
                for (Character chr : participants) {
                    botsInConversation.remove(chr.getId());
                }
            }
        });
    }

    private ConversationScript pickScript(int maxParticipants) {
        List<ConversationScript> eligible = new ArrayList<>();
        for (ConversationScript script : allScripts) {
            if (script.getParticipantCount() <= maxParticipants
                    && !recentScriptIds.contains(script.getId())) {
                eligible.add(script);
            }
        }

        if (eligible.isEmpty()) {
            for (ConversationScript script : allScripts) {
                if (script.getParticipantCount() <= maxParticipants) {
                    eligible.add(script);
                }
            }
        }

        if (eligible.isEmpty()) return null;
        return eligible.get(random.nextInt(eligible.size()));
    }

    private void playConversation(List<Character> participants, ConversationScript script) {
        Map<String, Character> roleMap = new HashMap<>();
        String[] roles = {"A", "B", "C", "D"};
        for (int i = 0; i < participants.size(); i++) {
            roleMap.put(roles[i], participants.get(i));
        }

        Point center = calculateCenter(participants);
        for (Character chr : participants) {
            faceTowardsPoint(chr, center);
        }
        blockingSleep(800);

        for (ConversationScript.ConversationLine line : script.getLines()) {
            if (!running) return;

            Character speaker = roleMap.get(line.getSpeaker());
            if (speaker == null) continue;

            if (line.getDelayMs() > 0) {
                blockingSleep((int) line.getDelayMs());
            }

            botSpeak(speaker, line.getText());
            if (line.hasEmote()) {
                botEmote(speaker, line.getEmote());
            }
        }

        blockingSleep(2000);
    }

    private Point calculateCenter(List<Character> participants) {
        int sumX = 0, sumY = 0;
        for (Character chr : participants) {
            sumX += chr.getPosition().x;
            sumY += chr.getPosition().y;
        }
        return new Point(sumX / participants.size(), sumY / participants.size());
    }

    // --- YAML Loading ---

    @SuppressWarnings("unchecked")
    private void loadScripts() {
        allScripts = new ArrayList<>();
        try (InputStream in = ConversationManager.class.getResourceAsStream(DIALOGUE_YAML)) {
            if (in == null) {
                log.warn("YAML resource not found: {}", DIALOGUE_YAML);
                return;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);
            if (root == null) return;
            Map<String, Object> conversations = (Map<String, Object>) root.get("conversations");
            if (conversations == null) return;

            for (Map.Entry<String, Object> entry : conversations.entrySet()) {
                String id = entry.getKey();
                Map<String, Object> convoMap = (Map<String, Object>) entry.getValue();

                int participants = toInt(convoMap.get("participants"));
                List<Object> linesList = (List<Object>) convoMap.get("lines");
                if (linesList == null) continue;

                List<ConversationScript.ConversationLine> parsedLines = new ArrayList<>();
                for (Object lineObj : linesList) {
                    Map<String, Object> lineMap = (Map<String, Object>) lineObj;
                    String speaker = (String) lineMap.get("speaker");
                    String text = (String) lineMap.get("text");
                    int emote = lineMap.containsKey("emote") ? toInt(lineMap.get("emote")) : -1;
                    long delay = 6000 + random.nextInt(3001);
                    parsedLines.add(new ConversationScript.ConversationLine(speaker, text, emote, delay));
                }

                allScripts.add(new ConversationScript(id, participants, parsedLines));
            }

            log.info("Loaded {} conversation scripts.", allScripts.size());
        } catch (Exception e) {
            log.warn("Failed to load conversation scripts", e);
        }
    }

    private static int toInt(Object obj) {
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        return 0;
    }

    private static long toLong(Object obj) {
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Integer) return ((Integer) obj).longValue();
        if (obj instanceof String) return Long.parseLong((String) obj);
        return 0;
    }

    // ── 等价实现（gms 无 SocialCommands / PlatformPlacement） ────────────────

    private static List<Character> getAllCharsOnMap(int mapId) {
        try {
            MapleMap map = Server.getInstance().getChannel(0, 1).getMapFactory().getMap(mapId);
            return map == null ? List.of() : new ArrayList<>(map.getAllPlayers());
        } catch (Exception e) {
            log.debug("ConversationManager.getAllCharsOnMap failed for map {}", mapId, e);
            return List.of();
        }
    }

    private static void faceTowardsPoint(Character chr, Point center) {
        if (chr == null || center == null) return;
        boolean left = center.x < chr.getPosition().x;
        if (GCMovement.isEnabled(chr)) {
            GCMovement.face(chr, left);
        } else {
            chr.broadcastStance(left ? 1 : 0);
        }
    }

    private static void botSpeak(Character character, String message) {
        if (character == null || character.getMap() == null) return;
        character.getMap().broadcastMessage(
                PacketCreator.getChatText(character.getId(), message, character.getWhiteChat(), 0));
    }

    private static void botEmote(Character character, int emote) {
        if (character == null || character.getMap() == null) return;
        character.getMap().broadcastMessage(PacketCreator.facialExpression(character, emote));
    }

    private static void blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

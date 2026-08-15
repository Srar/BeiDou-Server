package org.gms.server.bot.dialogue;

import java.util.List;

/**
 * 一段对话脚本（对应 SoloMapling ConversationScript 1:1 移植）。
 */
public class ConversationScript {

    private final String id;
    private final int participantCount;
    private final List<ConversationLine> lines;

    public ConversationScript(String id, int participantCount, List<ConversationLine> lines) {
        this.id = id;
        this.participantCount = participantCount;
        this.lines = lines;
    }

    public String getId() { return id; }
    public int getParticipantCount() { return participantCount; }
    public List<ConversationLine> getLines() { return lines; }

    public static class ConversationLine {
        private final String speaker;
        private final String text;
        private final int emote;
        private final long delayMs;

        public ConversationLine(String speaker, String text, int emote, long delayMs) {
            this.speaker = speaker;
            this.text = text;
            this.emote = emote;
            this.delayMs = delayMs;
        }

        public String getSpeaker() { return speaker; }
        public String getText() { return text; }
        public int getEmote() { return emote; }
        public boolean hasEmote() { return emote >= 0; }
        public long getDelayMs() { return delayMs; }
    }
}

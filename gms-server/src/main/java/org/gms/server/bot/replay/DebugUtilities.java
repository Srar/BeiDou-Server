package org.gms.server.bot.replay;

/**
 * 移植自 SoloMapling {@code DebugUtilities.debugprint}：编译期禁用的 no-op 调试输出。
 * gms 中未保留 SoloMapling 的 DebugUtilities（gcmove 层已用 slf4j log.debug 等价替换），
 * 此处提供同签名 no-op，供录制回放层逐行移植时保留 debugprint 调用点。
 */
public final class DebugUtilities {

    private DebugUtilities() {
    }

    /**
     * 编译期禁用的调试输出。源实现（printDebug=false）恒为 no-op，故此处保持空实现。
     */
    public static void debugprint(Object... variables) {
        // no-op
    }
}

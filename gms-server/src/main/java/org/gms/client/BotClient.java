package org.gms.client;

import org.gms.net.packet.Packet;

/**
 * 供全部 bot 共享的单一无头 Client（对应 SoloMapling 的 BotClient）：
 * 没有 socket、没有会话，只承载 world/channel 路由与「已登录」语义。
 * 网络副作用逐项中和——bot 角色仍然走玩家代码路径，但收发包与断线/闲置
 * 回收对它全部失效（角色侧 isLoggedin() 保持 false，跳过存档与断线循环）。
 */
public class BotClient extends Client {

    public BotClient(int world, int channel) {
        // type/sessionId/remoteAddress/packetProcessor 全空——只有 world+channel 是承重的（路由）
        super(null, -1, "bot", null, world, channel);
    }

    /**
     * no-op：bot 没有网络 socket。基类实现无 null 防护地调用
     * ioChannel.writeAndFlush(packet)，这是无头客户端最核心的 NPE 修复点。
     */
    @Override
    public void sendPacket(Packet packet) {
        // 发给 bot 的包直接消散——没有 socket，没有接收者
    }

    /**
     * bot 必须始终读作已登录：共享的玩家代码路径以它为门槛。
     * （与角色侧 Character.isLoggedin() 相互独立——后者保持 false 以跳过 autosave/disconnect。）
     */
    @Override
    public boolean isLoggedIn() {
        return true;
    }

    /** no-op：基类会 ioChannel.disconnect()。bot 永不掉线。 */
    @Override
    public void disconnectSession() {
        // 没有 socket 可断
    }

    /** no-op：基类会 ioChannel.close()。bot 没有会话可关。 */
    @Override
    public void closeSession() {
        // 没有会话可关
    }

    /**
     * 恒返回「刚刚」：任何超时任务都会把它视为刚活跃，共享 bot client 永不被判闲置回收。
     */
    @Override
    public long getLastPacket() {
        return System.currentTimeMillis();
    }
}

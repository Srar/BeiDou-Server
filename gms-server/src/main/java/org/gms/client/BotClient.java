package org.gms.client;

import io.netty.handler.timeout.IdleStateEvent;
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

    /**
     * bot 会话标记：容量统计（World/Channel 的 channel_capacity 上限）据此排除 bot——
     * loadenv 会生成数千 bot 注册进 PlayerStorage，计入真实容量会挤掉所有真实玩家登录。
     */
    @Override
    public boolean isBot() {
        return true;
    }

    /**
     * no-op：无头客户端绝不能写登录态。基类会向 accounts 表写 loggedin 行
     * （按 getAccID() 定位，bot 的 accId 为 -4，会污染真实数据）并注册/注销
     * 在线会话；bot 没有账号行、没有会话，此处直接跳过。
     */
    @Override
    public void updateLoginState(int state) {
        // headless：绝不写 accounts loggedin 行、不注册在线会话
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
     * no-op：基类 idle 检查会 ping 后引用 ioChannel.isActive() 并可能断开。
     * bot 没有 ioChannel，永不在 netty pipeline 中——此覆写保证闲置回收器
     * 绝不可能碰到共享 bot client。
     */
    @Override
    public void checkIfIdle(final IdleStateEvent event) {
        // 无 ioChannel，永不回收共享无头客户端
    }

    /**
     * 恒返回「刚刚」：任何超时任务都会把它视为刚活跃，共享 bot client 永不被判闲置回收。
     */
    @Override
    public long getLastPacket() {
        return System.currentTimeMillis();
    }
}

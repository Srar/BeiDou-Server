/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation version 3 as published by
the Free Software Foundation. You may not use, modify or distribute
this program under any other version of the GNU Affero General Public
License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.command.CommandsExecutor;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.buffrequest.BotBuffRequestHandler;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.ChatLogger;
import org.gms.util.PacketCreator;

public final class GeneralChatHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(GeneralChatHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        String s = p.readString();
        Character chr = c.getPlayer();
        if (chr.getAutoBanManager().getLastSpam(7) + 200 > currentServerTime()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (s.length() > Byte.MAX_VALUE && !chr.isGM()) {
            AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit in General Chat.");
            log.warn("Chr {} tried to send text with length of {}", c.getPlayer().getName(), s.length());
            c.disconnect(true, false);
            return;
        }
        char heading = s.charAt(0);
        if (CommandsExecutor.isCommand(c, s)) {
            CommandsExecutor.getInstance().handle(c, s);
        } else if (heading != '/') {
            int show = p.readByte();
            if (chr.getMap().isMuted() && !chr.isGM()) {
                chr.dropMessage(5, "The map you are in is currently muted. Please try again later.");
                return;
            }

            if (!chr.isHidden()) {
                chr.getMap().broadcastMessage(PacketCreator.getChatText(chr.getId(), s, chr.getWhiteChat(), show));
                ChatLogger.log(c, "General", s);
            } else {
                chr.getMap().broadcastGMMessage(PacketCreator.getChatText(chr.getId(), s, chr.getWhiteChat(), show));
                ChatLogger.log(c, "GM General", s);
            }

            chr.getAutoBanManager().spam(7);

            // Bot 框架：非隐藏且非 bot 玩家的聊天发布 CHAT 事件，供同图 bot 在自身 tick 内应答。
            // 放在 spam(7) 之后：订阅者异常（理论上总线契约禁止）也不会跳过反刷屏计数。
            // 隐身 GM 的发言只对 GM 可见，不进 bot 事件流（否则 bot 公开应答会暴露 GM 隐身）。
            if (!chr.isHidden()) {
                // bot 聊天不回喂：CHAT 事件与 primary 队列都不接收 bot 自己的聊天
                //（防 bot 互相点名循环；与下方 tryHandle 内部的 isBot 守卫一致）。
                if (!BotHelpers.isBot(chr)) {
                    BotEventBus.getInstance().publish(GameEvent.chat(chr.getWorld(), chr.getClient().getChannel(), chr.getMapId(), chr.getId(), s));
                    // 聊天点名链路接线（修复）：Dispatcher 每 2s 轮询 primary 队列做「消息含 bot 名字」
                    // 的点名匹配——SocialBot 对话菜单 / TrainingBot 菜单 / FollowerBot "train here" 转职
                    // / 黑杰克 join 等全部依赖此队列。此前 primary 队列无任何生产者、CHAT 事件又无
                    // 订阅者，聊天点名交互整体断线。CHAT 事件与 primary 队列双轨并存：事件供未来
                    // 订阅者，队列驱动现有 Dispatcher 点名链路。
                    MessageQueue.getInstance().addMessage("primary", new ChatMessage(chr, s));
                }
                // 求 buff 入口：对齐 SoloMapling 源（普通聊天分支，事件/队列处理之后再 tryHandle）。
                // tryHandle 内部已有 isBot 守卫（bot 聊天永不回喂）；此处与 CHAT 事件同处 !isHidden 分支，
                // 保证隐身 GM 发言不触发 bot 反应（bot 公开 emote/气泡会暴露 GM 隐身）。
                BotBuffRequestHandler.tryHandle(chr, s);
            }
        }
    }
}
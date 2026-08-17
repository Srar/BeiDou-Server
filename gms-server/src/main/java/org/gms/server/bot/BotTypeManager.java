package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.types.BuyingMerchantBot;
import org.gms.server.bot.types.DiceBot;
import org.gms.server.bot.types.DropGameBot;
import org.gms.server.bot.types.FMBot;
import org.gms.server.bot.types.FollowerBot;
import org.gms.server.bot.types.GachaBot;
import org.gms.server.bot.types.GameZoneHostBot;
import org.gms.server.bot.types.HenesysBot;
import org.gms.server.bot.types.HenesysJQBot;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.bot.types.NXMerchantBot;
import org.gms.server.bot.types.ScrollingBot;
import org.gms.server.bot.types.SellingMerchantBot;
import org.gms.server.bot.types.SocialBot;
import org.gms.server.bot.types.TestAttackBot;
import org.gms.server.bot.types.TownWandererBot;
import org.gms.server.bot.types.TrainingBot;
import org.gms.server.bot.types.TutorialBot;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.types.blackjack.BlackjackDealerBot;
import org.gms.server.bot.types.opq.OPQBot;
import org.gms.util.I18nUtil;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Bot 类型工厂与生命周期命令（参考 SoloMapling 的 BotTypeManager 移植）：
 * 类型枚举负责「把角色包进对应的 BotSM 并注册」，静态方法负责启动/停止/换型/批量。
 */
@Slf4j
public final class BotTypeManager {

    private BotTypeManager() {
    }

    public enum BotType {
        IDLE_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new IdleBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        DICE_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new DiceBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        TUTORIAL_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new TutorialBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        FM_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new FMBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        SCROLL_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new ScrollingBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        SELLING_MERCHANT_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new SellingMerchantBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        BUYING_MERCHANT_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new BuyingMerchantBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        NX_MERCHANT_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new NXMerchantBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        GACHA_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new GachaBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        HENESYS_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new HenesysBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        HENESYS_JQ_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new HenesysJQBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        GAME_ZONE_HOST_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new GameZoneHostBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        BLACKJACK_DEALER {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new BlackjackDealerBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        DROP_GAME_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new DropGameBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        OPQ_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new OPQBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        SOCIAL_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new SocialBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        TOWN_WANDERER_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new TownWandererBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        TEST_ATTACK_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new TestAttackBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        TRAINING_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new TrainingBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        },
        FOLLOWER_BOT {
            @Override
            public BotSM createAndSetBot(Character character) {
                BotSM bot = new FollowerBot(character);
                BotStorage.addActiveBot(character.getId(), bot);
                return bot;
            }
        };

        public abstract BotSM createAndSetBot(Character character);
    }

    /**
     * 启动 bot：置 running 并挂上 tick 轮。首 tick 延迟越过出生编排窗口
     * （7-10s），既保证 bot 不会在到场动画中途开动，也错开批量生成的首拍。
     */
    public static void manuallyStartBot(Character fakechar) {
        BotSM bot = BotStorage.getBotById(fakechar.getId());
        if (bot == null || bot.getRunning()) {
            return;
        }
        bot.setRunning(true);
        long initialDelay = BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS
                + ThreadLocalRandom.current().nextLong(0, 3000);
        bot.startScheduledTask(initialDelay);
    }

    public static void manuallyStopBot(Character fakechar) {
        BotSM bot = BotStorage.getBotById(fakechar.getId());
        if (bot == null) {
            return;
        }
        bot.setRunning(false);
        bot.stopScheduledTask();
    }

    /**
     * 原地换型：停旧 FSM，把同一个 Character 包进新类型并重启。
     * 交易中拒绝（交易神圣不可撕——换型会搁浅交易对手）。v1 无交易子系统，仍保留防护。
     */
    public static boolean convertBotType(Character fakechar, BotType botType) {
        return convertBotType(fakechar, botType, null);
    }

    /**
     * 原地换型（带转换产物回调）。
     * <p>
     * onConverted：与 createAndSetBot 同线程、紧邻其后触发，直接拿到刚注册的新 BotSM——
     * 跨型交接状态（如 JQ 冷却）由回调原子设置，不再由调用方在 convert 返回后回读注册表
     * （彼时实例可能已被并发转换/替换，冷却会落到错误对象，闸门失效引发 ping-pong 加速）。
     */
    public static boolean convertBotType(Character fakechar, BotType botType, Consumer<BotSM> onConverted) {
        BotSM existing = BotStorage.getBotById(fakechar.getId());
        if (existing != null) {
            if (existing.getState() == BotSM.BotState.TRADING) {
                log.warn(I18nUtil.getLogMessage("BotTypeManager.convert.refused", fakechar.getName()));
                return false;
            }
            manuallyStopBot(fakechar);
        }
        // 无条件释放 gcmove 移动引擎资源（m1）：existing == null 的半移除态也可能残留
        // BotMovementState（注册表已被并发摘除而状态未清），disable 幂等廉价，换型前必清。
        // 旧 FSM 已停：BotMovementState 持有 Character 引用，转换风暴中不 disable 会随
        // 换型永久泄漏并积压 driver 任务。
        GCMovement.disable(fakechar);
        BotSM converted = botType.createAndSetBot(fakechar);
        if (onConverted != null) {
            try {
                onConverted.accept(converted);
            } finally {
                // 回调异常不能跳过启动：新 bot 已注册，跳过 start 会成僵尸（不在 tick 轮）。
                manuallyStartBot(fakechar);
            }
        } else {
            manuallyStartBot(fakechar);
        }
        return true;
    }

    public static void startAllBots() {
        for (BotSM bot : BotStorage.getAllBots().values()) {
            manuallyStartBot(bot.getChr());
        }
    }

    public static void stopAllBots() {
        for (BotSM bot : BotStorage.getAllBots().values()) {
            manuallyStopBot(bot.getChr());
        }
    }
}

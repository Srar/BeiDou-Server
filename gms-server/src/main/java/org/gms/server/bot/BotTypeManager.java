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
import org.gms.server.bot.types.blackjack.BlackjackDealerBot;
import org.gms.server.bot.types.opq.OPQBot;
import org.gms.util.I18nUtil;

import java.util.concurrent.ThreadLocalRandom;

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
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new IdleBot(character));
            }
        },
        DICE_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new DiceBot(character));
            }
        },
        TUTORIAL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new TutorialBot(character));
            }
        },
        FM_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new FMBot(character));
            }
        },
        SCROLL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new ScrollingBot(character));
            }
        },
        SELLING_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new SellingMerchantBot(character));
            }
        },
        BUYING_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new BuyingMerchantBot(character));
            }
        },
        NX_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new NXMerchantBot(character));
            }
        },
        GACHA_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new GachaBot(character));
            }
        },
        HENESYS_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new HenesysBot(character));
            }
        },
        HENESYS_JQ_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new HenesysJQBot(character));
            }
        },
        GAME_ZONE_HOST_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new GameZoneHostBot(character));
            }
        },
        BLACKJACK_DEALER {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new BlackjackDealerBot(character));
            }
        },
        DROP_GAME_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new DropGameBot(character));
            }
        },
        OPQ_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new OPQBot(character));
            }
        },
        SOCIAL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new SocialBot(character));
            }
        },
        TOWN_WANDERER_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new TownWandererBot(character));
            }
        },
        TEST_ATTACK_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new TestAttackBot(character));
            }
        },
        TRAINING_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new TrainingBot(character));
            }
        },
        FOLLOWER_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BotStorage.addActiveBot(character.getId(), new FollowerBot(character));
            }
        };

        public abstract void createAndSetBot(Character character);
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
        BotSM existing = BotStorage.getBotById(fakechar.getId());
        if (existing != null) {
            if (existing.getState() == BotSM.BotState.TRADING) {
                log.warn(I18nUtil.getLogMessage("BotTypeManager.convert.refused", fakechar.getName()));
                return false;
            }
            manuallyStopBot(fakechar);
        }
        botType.createAndSetBot(fakechar);
        manuallyStartBot(fakechar);
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

package relaySpeedrun;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 *
 */
public class RelaySpeedrun implements ModInitializer {
    
    public static final String MOD_ID = "relay-speedrun";
    
    public static final Logger LOGGER = LoggerFactory.getLogger("Relay Speedrun");
    
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("relay").then(literal("start").executes(context -> {
                ServerCommandSource source = context.getSource();
                return switch (Relay.getCurrentState()) {
                    case BEFORE_START -> {
                        if (source.getServer().getPlayerManager().getPlayerList().isEmpty()) {
                            source.sendMessage(Text.literal("Relay cannot start because there is no players"));
                            yield 0;
                        }
                        
                        Relay.start();
                        yield 1;
                    }
                    case RUNNING, PAUSING, FORCED_PAUSING -> {
                        source.sendMessage(Text.literal("Relay has already started"));
                        yield 0;
                    }
                    case ENDED -> {
                        source.sendMessage(Text.literal("Relay has already ended"));
                        yield 0;
                    }
                };
            })).then(literal("stop").executes(context -> {
                ServerCommandSource source = context.getSource();
                return switch (Relay.getCurrentState()) {
                    case BEFORE_START -> {
                        source.sendMessage(Text.literal("Relay has not started yet"));
                        yield 0;
                    }
                    case RUNNING, PAUSING, FORCED_PAUSING -> {
                        Relay.stop();
                        yield 1;
                    }
                    case ENDED -> {
                        source.sendMessage(Text.literal("Relay has already ended"));
                        yield 0;
                    }
                };
            })).then(literal("pause").executes(context -> {
                ServerCommandSource source = context.getSource();
                return switch (Relay.getCurrentState()) {
                    case BEFORE_START -> {
                        source.sendMessage(Text.literal("Relay has not started yet"));
                        yield 0;
                    }
                    case RUNNING -> {
                        Relay.pause();
                        yield 1;
                    }
                    case PAUSING, FORCED_PAUSING -> {
                        source.sendMessage(Text.literal("Relay has already paused"));
                        yield 0;
                    }
                    case ENDED -> {
                        source.sendMessage(Text.literal("Relay has already ended"));
                        yield 0;
                    }
                };
            })).then(literal("resume").executes(context -> {
                ServerCommandSource source = context.getSource();
                return switch (Relay.getCurrentState()) {
                    case BEFORE_START -> {
                        source.sendMessage(Text.literal("Relay has not started yet"));
                        yield 0;
                    }
                    case RUNNING -> {
                        source.sendMessage(Text.literal("Relay is already running"));
                        yield 0;
                    }
                    case PAUSING -> {
                        Relay.resume();
                        yield 1;
                    }
                    case FORCED_PAUSING -> {
                        source.sendMessage(Text.literal("Relay cannot resume because the current player is not online"));
                        yield 0;
                    }
                    case ENDED -> {
                        source.sendMessage(Text.literal("Relay has already ended"));
                        yield 0;
                    }
                };
            })).then(literal("countdown").then(literal("get").executes(context -> {
                context.getSource().sendMessage(Text.literal("Counting down from " + Relay.countdown));
                return Relay.countdown;
            })).then(literal("set").then(argument("countdown", IntegerArgumentType.integer(0)).executes(context -> {
                Relay.countdown = IntegerArgumentType.getInteger(context, "countdown");
                context.getSource().sendMessage(Text.literal("Countdown set to " + Relay.countdown));
                return 1;
            })))));
            //@formatter:off
            dispatcher.register(literal("setvelocity").then(argument("entity", EntityArgumentType.entity()).then(argument("velocityX",
              DoubleArgumentType.doubleArg()).then(argument("velocityY", DoubleArgumentType.doubleArg()).then(argument(
              "velocityZ",
              DoubleArgumentType.doubleArg()).executes(context -> {
                Entity entity = EntityArgumentType.getEntity(context, "entity");
                if (!(entity.getEntityWorld() instanceof ServerWorld serverWorld)) return 0;
                entity.teleportTo(new TeleportTarget(
                  serverWorld,
                  new Vec3d(entity.getX(), entity.getY(), entity.getZ()),
                  new Vec3d(
                    DoubleArgumentType.getDouble(context, "velocityX"),
                    DoubleArgumentType.getDouble(context, "velocityY"),
                    DoubleArgumentType.getDouble(context, "velocityZ")),
                  entity.getYaw(),
                  entity.getPitch(),
                  TeleportTarget.NO_OP));
                return 1;
            }))))));
            //@formatter:on
        });
        
        ServerLifecycleEvents.SERVER_STARTED.register(Relay::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Relay.save());
        
        ServerTickEvents.END_SERVER_TICK.register(Relay::tick);
        
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sender.sendPacket(new TitleFadeS2CPacket(0, 3, 0));
            Relay.join(handler.player);
        });
    }
    
}

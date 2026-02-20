package relaySpeedrun;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.JsonOps;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.scoreboard.*;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import relaySpeedrun.mixin.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;

import static relaySpeedrun.RelaySpeedrun.LOGGER;

public class Relay {
    
    private static final Gson        GSON  = new Gson();
    private static final Path        RELAY = Paths.get("relay.json");
    private static final ScoreHolder IGT, RTA;
    
    /// {@code timer} counts down from here
    public static  int                      countdown = 1200;
    private static State                    state     = State.BEFORE_START;
    private static List<ServerPlayerEntity> players;
    private static ServerPlayerEntity       current;
    private static UUID                     currentPlayerUuid;
    private static Text                     currentPlayerName;
    private static int                      timer, rta, igt;
    
    private static Scoreboard  scoreboard;
    private static ScoreAccess rtaScore, igtScore;
    private static Team spectator;
    
    static {
        IGT = new ScoreHolder() {
            
            @Override
            public String getNameForScoreboard() { return "igt"; }
            
            @Override
            public Text getDisplayName() {
                return igtScore == null ? null : Text.literal("IGT " + getCountdown(igt)).withColor(0x00ffff);
            }
            
        };
        RTA = new ScoreHolder() {
            
            @Override
            public String getNameForScoreboard() { return "rta"; }
            
            @Override
            public Text getDisplayName() {
                return rtaScore == null ? null : Text.literal("RTA " + getCountdown(rta)).withColor(0xff00ff);
            }
            
        };
    }
    
    public static State getCurrentState() { return state; }
    
    public static void init(MinecraftServer server) {
        PlayerManager playerManager = server.getPlayerManager();
        players = playerManager.getPlayerList();
        
        // init scoreboard
        scoreboard = server.getScoreboard();
        ScoreboardObjective timerObjective = scoreboard.getNullableObjective("timer");
        if (timerObjective == null) timerObjective = scoreboard.addObjective(
          "timer",
          ScoreboardCriterion.DUMMY,
          Text.literal("Timer"),
          ScoreboardCriterion.RenderType.INTEGER,
          true,
          BlankNumberFormat.INSTANCE);
        (rtaScore = scoreboard.getOrCreateScore(RTA, timerObjective)).setScore(1);
        (igtScore = scoreboard.getOrCreateScore(IGT, timerObjective)).setScore(0);
        if ((spectator = scoreboard.getTeam("spectator")) == null) spectator = scoreboard.addTeam("spectator");
        spectator.setColor(Formatting.WHITE);
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.TEAM_WHITE, timerObjective);
        
        // read json
        if (!new File(RELAY.toString()).exists()) return;
        JsonObject json;
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(Relay.RELAY))) {
            json = Relay.GSON.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            LOGGER.error("error", e);
            throw new RuntimeException(e);
        }
        
        // read state
        state = State.parse(json.get("state").getAsString());
        if (state == null) {
            LOGGER.error("Unknown state: {}", json.get("state").getAsString());
            return;
        }
        
        if (state == State.ENDED) return;
        
        // read other data
        countdown = json.get("countdown").getAsInt();
        timer     = json.get("timer").getAsInt();
        rta       = json.get("rta").getAsInt();
        igt       = json.get("igt").getAsInt();
        updateScore(rtaScore);
        updateScore(igtScore);
        if (state.isTicking()) {
            JsonObject currentPlayer = json.getAsJsonObject("currentPlayer");
            if ((current = playerManager.getPlayer(
              currentPlayerUuid = UUID.fromString(currentPlayer.get("uuid").getAsString()))) == null) state
              = State.FORCED_PAUSING;
            else state = State.PAUSING;
            currentPlayerName = TextCodecs.CODEC
              .decode(JsonOps.INSTANCE, currentPlayer.get("name"))
              .getOrThrow()
              .getFirst();
        }
    }
    
    public static void save() {
        if (state == State.RUNNING) pause();
        
        JsonObject json = new JsonObject();
        json.addProperty("state", state.toString());
        json.addProperty("countdown", Relay.countdown);
        json.addProperty("timer", timer);
        json.addProperty("rta", rta);
        json.addProperty("igt", igt);
        JsonObject currentPlayer = new JsonObject();
        if (currentPlayerUuid != null && currentPlayerName != null) {
            currentPlayer.addProperty("uuid", currentPlayerUuid.toString());
            currentPlayer.add("name", TextCodecs.CODEC.encodeStart(JsonOps.INSTANCE, currentPlayerName).getOrThrow());
        }
        json.add("currentPlayer", currentPlayer);
        
        try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(RELAY))) {
            writer.setIndent("  ");
            Relay.GSON.toJson(json, writer);
        } catch (IOException e) { LOGGER.error("error", e); }
    }
    
    public static void join(ServerPlayerEntity player) {
        switch (state) {
            case State.FORCED_PAUSING:
                if (player.getUuid().equals(currentPlayerUuid)) {
                    current = player;
                    state   = State.PAUSING;
                    break;
                }
            case State.RUNNING, State.PAUSING:
                makeSpectator(player);
        }
    }
    
    public static void start() {
        players.forEach(Relay::makeSpectator);
        takeOver(players.getFirst());
        state = State.RUNNING;
        LOGGER.info("Relay started");
    }
    
    public static void pause() {
        state = State.PAUSING;
        LOGGER.info("Relay paused");
    }
    
    public static void resume() {
        state = State.RUNNING;
        LOGGER.info("Relay resumed");
    }
    
    public static void stop() {
        for (ServerPlayerEntity player : players) {
            if (player == current) continue;
            
            player.changeGameMode(GameMode.SURVIVAL);
            clearEffects(player);
            removeScoreHolderFromTeam(player, spectator);
        }
        
        if (current != null) for (ServerPlayerEntity spectator : players)
            if (spectator != current) spectator.teleport(
              current.getEntityWorld(),
              current.getX(),
              current.getY(),
              current.getZ(),
              Set.of(),
              current.getYaw(),
              current.getPitch(),
              false);
        
        state             = State.BEFORE_START;
        current           = null;
        currentPlayerUuid = null;
        currentPlayerName = null;
        LOGGER.info("Relay stopped");
    }
    
    public static void tick(MinecraftServer server) {
        if (!state.isTicking()) return;
        rta++;
        updateScore(rtaScore);
        
        // handle scheduled teleport
        //        if (teleportTarget != null) {
        //            current.teleportTo(teleportTarget);
        //            teleportTarget = null;
        //        }
        
        // update ref
        if (current != null) for (ServerPlayerEntity player : players)
            if (current.equals(player) && current != player) {
                current = player;
                break;
            }
        
        // fix spectators in place
        for (ServerPlayerEntity spectator : players)
            if (spectator != current) spectator.teleport(
              spectator.getEntityWorld(),
              0d,
              320d,
              0d,
              Set.of(),
              0f,
              0f,
              false);
        
        // pause if current player left
        if (!players.contains(current) && state != State.FORCED_PAUSING) {
            current = null;
            state   = State.FORCED_PAUSING;
            
            players.forEach(player -> player.sendMessage(Text
              .literal("Current player left, relay paused")
              .withColor(0xff0000)));
            LOGGER.info("Current player left, relay paused");
        }
        
        String currentPlayerCountdown = getCurrentPlayerCountdown();
        
        // when paused
        if (state.isPaused()) {
            Text msg = Text.literal("Game Paused").withColor(0xff0000);
            for (ServerPlayerEntity player : players)
                if (player == current) player.networkHandler.sendPacket(new OverlayMessageS2CPacket(msg));
                else {
                    player.networkHandler.sendPacket(new SubtitleS2CPacket(Text
                      .literal("Current player: ")
                      .append(currentPlayerName)
                      .append(' ' + currentPlayerCountdown)));
                    player.networkHandler.sendPacket(new TitleS2CPacket(msg));
                }
            return;
        }
        
        // show titles
        ServerPlayerEntity next = getNextPlayer();
        for (ServerPlayerEntity player : players) {
            if (player == current) {
                player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.literal(currentPlayerCountdown)));
                continue;
            }
            
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text
              .literal("Current player: ")
              .append(currentPlayerName)
              .append(' ' + currentPlayerCountdown)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(getTotalCountdown(player))));
            player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text
              .literal("Next player: " + next.getStringifiedName())
              .styled(player == next ? style -> style.withColor(0x00ff00) : UnaryOperator.identity())));
        }
        
        // on win
        if (current.seenCredits) {
            stop();
            state = State.ENDED;
            
            PlayerManager playerManager = server.getPlayerManager();
            playerManager.sendToAll(new SubtitleS2CPacket(Text
              .literal("RTA " + getCountdown(rta))
              .withColor(0xff00ff)));
            playerManager.sendToAll(new TitleS2CPacket(Text.literal("IGT " + getCountdown(igt)).withColor(0x00ffff)));
            
            return;
        }
        
        // handle taking over
        if (timer % 20 == 0) LOGGER.debug("Timer: {}", currentPlayerCountdown);
        if (timer == 0) takeOver(next);
        timer--;
        igt++;
        updateScore(igtScore);
    }
    
    private static void updateScore(ScoreAccess score) { score.setScore(score.getScore()); }
    
    private static String getCurrentPlayerCountdown()  { return getCountdown(timer); }
    
    private static String getTotalCountdown(ServerPlayerEntity player) {
        int queuePos = players.indexOf(player) - players.indexOf(current) - 1;
        if (queuePos < 0) queuePos += players.size();
        return getCountdown(timer + queuePos * countdown);
    }
    
    private static String getCountdown(int ticks) {
        StringBuilder sb = new StringBuilder();
        
        int tenms     = ticks * 5;
        int hundredms = tenms / 10;
        int sec       = hundredms / 10;
        int tensec    = sec / 10;
        int min       = tensec / 6;
        int tenmin    = min / 10;
        int hour      = tenmin / 6;
        
        tenms %= 10;
        hundredms %= 10;
        sec %= 10;
        tensec %= 6;
        min %= 10;
        tenmin %= 6;
        
        if (hour > 0) {
            sb.append(hour).append(':');
            if (tenmin == 0) sb.append(0);
        }
        if (tenmin > 0 && min == 0) sb.append(0).append(':');
        if (min > 0) {
            sb.append(min).append(':');
            if (tensec == 0) sb.append(0);
        }
        if (tensec > 0) sb.append(tensec);
        sb.append(sec).append('.').append(hundredms).append(tenms);
        
        return sb.toString();
    }
    
    private static ServerPlayerEntity getNextPlayer() {
        return players.get((players.indexOf(current) + 1) % players.size());
    }
    
    private static void makeSpectator(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SPECTATOR);
        clearEffects(player);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(
          StatusEffects.SATURATION,
          Integer.MAX_VALUE,
          255,
          false,
          false));
        addScoreHolderToTeam(player, spectator);
    }
    
    private static void takeOver(ServerPlayerEntity player) {
        timer             = countdown;
        currentPlayerUuid = player.getUuid();
        currentPlayerName = player.getDisplayName();
        
        player.changeGameMode(GameMode.SURVIVAL);
        if (current == null) {
            clearEffects(player);
            removeScoreHolderFromTeam(player, spectator);
            current = player;
            return;
        }
        
        LOGGER.info("{} takes over RTA: {} IGT: {}", player.getStringifiedName(), getCountdown(rta), getCountdown(igt));
        
        if (current == player) return;
        replaceTeam(player);
        replaceFallDistance(player);
        replacePortalCooldown(player);
        replaceHP(player);
        replaceHunger(player);
        replaceAir(player);
        replaceXp(player);
        replaceStatusEffects(player);
        replaceSelectedHotbarSlot(player);
        replaceInventory(player);
        replaceEnderChestInventory(player);
        replaceSpawnpoint(player);
        replaceShoulders(player);
        replaceSculkShriekerWarnings(player);
        iterateEntities(player);
        if (!replaceRiding(player)) tp(player);
        makeSpectator(current);
        
        current = player;
    }
    
    private static void replaceTeam(ServerPlayerEntity player) {
        removeScoreHolderFromTeam(player, spectator);
        addScoreHolderToTeam(current, spectator);
    }
    
    private static void replaceFallDistance(ServerPlayerEntity player) { player.fallDistance = current.fallDistance; }
    
    private static void replacePortalCooldown(
      ServerPlayerEntity player) { player.setPortalCooldown(current.getPortalCooldown()); }
    
    private static void replaceHP(ServerPlayerEntity player) { player.setHealth(current.getHealth()); }
    
    private static void replaceHunger(ServerPlayerEntity player) {
        HungerManager playerhm  = player.getHungerManager();
        HungerManager currenthm = current.getHungerManager();
        playerhm.setFoodLevel(currenthm.getFoodLevel());
        playerhm.setSaturationLevel(currenthm.getSaturationLevel());
        ((HungerManagerMixin) playerhm).setExhaustion(((HungerManagerMixin) currenthm).getExhaustion());
    }
    
    private static void replaceAir(ServerPlayerEntity player) { player.setAir(current.getAir()); }
    
    private static void replaceXp(ServerPlayerEntity player) {
        player.experienceLevel    = current.experienceLevel;
        player.experienceProgress = current.experienceProgress;
        player.totalExperience    = current.totalExperience;
    }
    
    private static void replaceStatusEffects(ServerPlayerEntity player) {
        clearEffects(player);
        for (StatusEffectInstance effect : current.getStatusEffects()) player.addStatusEffect(effect);
    }
    
    private static void replaceSelectedHotbarSlot(ServerPlayerEntity player) {
        player.getInventory().setSelectedSlot(current.getInventory().getSelectedSlot());
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().getSelectedSlot()));
    }
    
    private static void replaceInventory(ServerPlayerEntity player) {
        PlayerScreenHandler playersh  = player.playerScreenHandler;
        PlayerScreenHandler currentsh = current.playerScreenHandler;
        
        ItemStack cursor = currentsh.getCursorStack();
        current.dropItem(cursor, false);
        currentsh.setCursorStack(ItemStack.EMPTY);
        
        List<ItemStack> craftingGrid = currentsh.getCraftingInput().getHeldStacks();
        for (ItemStack itemStack : craftingGrid) current.dropItem(itemStack, false);
        
        for (int p = 0, c = 0; playersh.isValid(p) && currentsh.isValid(c); p++, c++) {
            Slot currentSlot = currentsh.getSlot(c);
            playersh.getSlot(p).setStack(currentsh.getSlot(c).getStack());
            currentSlot.setStack(ItemStack.EMPTY);
        }
    }
    
    private static void replaceEnderChestInventory(ServerPlayerEntity player) {
        EnderChestInventory playereci  = player.getEnderChestInventory();
        EnderChestInventory currenteci = current.getEnderChestInventory();
        
        for (int i = 0; i < playereci.size(); i++) {
            playereci.setStack(i, currenteci.getStack(i));
            currenteci.setStack(i, ItemStack.EMPTY);
        }
    }
    
    private static void replaceSpawnpoint(ServerPlayerEntity player) { player.setSpawnPointFrom(current); }
    
    private static void replaceShoulders(ServerPlayerEntity player) {
        ServerPlayerEntityMixin currentmx        = (ServerPlayerEntityMixin) current;
        ServerPlayerEntityMixin playermx         = (ServerPlayerEntityMixin) player;
        NbtCompound             leftShoulderNbt  = current.getLeftShoulderNbt();
        NbtCompound             rightShoulderNbt = current.getRightShoulderNbt();
        
        if (!leftShoulderNbt.isEmpty()) {
            playermx.relay_speedrun$setLeftShoulderNbt(current.getLeftShoulderNbt());
            currentmx.relay_speedrun$setLeftShoulderNbt(new NbtCompound());
        }
        if (!rightShoulderNbt.isEmpty()) {
            playermx.relay_speedrun$setRightShoulderNbt(current.getRightShoulderNbt());
            currentmx.relay_speedrun$setRightShoulderNbt(new NbtCompound());
        }
    }
    
    private static void replaceSculkShriekerWarnings(ServerPlayerEntity player) {
        current.getSculkShriekerWarningManager().ifPresent(sswm -> {
            ((SculkShriekerWarningManagerMixin) player
              .getSculkShriekerWarningManager()
              .orElseThrow()).relay_speedrun$copy(current.getSculkShriekerWarningManager().orElseThrow());
            sswm.reset();
        });
    }
    
    private static void iterateEntities(ServerPlayerEntity player) {
        for (Entity entity : current.getEntityWorld().iterateEntities())
            switch (entity) {
                case MobEntity mob when mob.getTarget() == current -> mob.setTarget(player);
                case TameableEntity tameable when tameable.getOwner() == current -> tameable.setOwner(player);
                case ProjectileEntity projectile when projectile.getOwner() == current -> projectile.setOwner(player);
                case TntEntity tnt when tnt.getOwner() == current -> ((TntEntityMixin) tnt).setCausingEntity(
                  LazyEntityReference.of(player));
                case ItemEntity item when item.getOwner() == current -> {
                    ItemEntityMixin itemmx = (ItemEntityMixin) item;
                    if (item.getOwner() == current) itemmx.setThrower(LazyEntityReference.of(player));
                    if (itemmx.getOwnerUuid().equals(current.getUuid())) itemmx.setOwnerUuid(currentPlayerUuid);
                }
                default -> { }
            }
    }
    
    private static boolean replaceRiding(ServerPlayerEntity player) {
        Entity vehicle = current.getVehicle();
        if (vehicle != null) {
            current.stopRiding();
            player.startRiding(vehicle);
            return true;
        }
        return false;
    }
    
    private static void tp(ServerPlayerEntity player) {
        ServerPlayNetworkHandlerMixin handler = (ServerPlayNetworkHandlerMixin) current.networkHandler;
        player.teleportTo(new TeleportTarget(
          current.getEntityWorld(), current.getEntityPos(), new Vec3d(
          current.getX() - handler.getLastTickX(),
          current.getY() - handler.getLastTickY(),
          current.getZ() - handler.getLastTickZ()), current.getYaw(), current.getPitch(), TeleportTarget.NO_OP));
    }
    
    private static void clearEffects(ServerPlayerEntity player) {
        new ArrayList<>(player.getStatusEffects()).forEach(effect -> player.removeStatusEffect(effect.getEffectType()));
    }
    
    private static void addScoreHolderToTeam(ScoreHolder scoreHolder, Team team) {
        String name = scoreHolder.getNameForScoreboard();
        scoreboard.addScoreHolderToTeam(name, team);
    }
    
    private static void removeScoreHolderFromTeam(ScoreHolder scoreHolder, Team team) {
        String name = scoreHolder.getNameForScoreboard();
        if (scoreboard.getScoreHolderTeam(name) == team) scoreboard.removeScoreHolderFromTeam(name, team);
    }
    
    public enum State {
        
        BEFORE_START, RUNNING, PAUSING, FORCED_PAUSING, ENDED;
        
        @Override
        public String toString() { return name().toLowerCase(Locale.ROOT); }
        
        public boolean isTicking() { return this != BEFORE_START && this != ENDED; }
        
        public boolean isPaused()  { return this == PAUSING || this == FORCED_PAUSING; }
        
        public static State parse(String str) {
            for (State state : values()) if (state.toString().equals(str)) return state;
            return null;
        }
        
    }
    
}

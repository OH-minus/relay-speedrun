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
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.scoreboard.*;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
    
    private static final Gson GSON = new Gson();
    
    /// The path to the file in which the current state of the relay speedrun is saved.
    private static final Path RELAY = Paths.get("relay.json");
    
    /// A score holder whose name displays information about the current RTA.
    private static final ScoreHolder RTA;
    
    /// A score holder whose name displays information about the current IGT.
    private static final ScoreHolder IGT;
    
    /// {@link Relay#timer} counts down from here
    public static int countdown = 1200;
    
    /// The current state of the relay speedrun.
    private static State state = State.BEFORE_START;
    
    /// The players in this server participating in the relay speedrun.
    private static List<ServerPlayerEntity> players;
    
    /// The current player.
    private static ServerPlayerEntity current;
    
    /// The UUID of the current player.
    private static UUID currentPlayerUuid;
    
    /// The display name of the current player.
    private static Text currentPlayerName;
    
    /// The time the current player has left, in ticks.
    private static int timer;
    
    /// The current RTA, in ticks.
    private static int rta;
    
    /// The current IGT, in ticks.
    private static int igt;
    
    /// The scoreboard instance of this server
    private static Scoreboard scoreboard;
    
    /// The score held by score holder {@link Relay#RTA}.
    private static ScoreAccess rtaScore;
    
    /// The score held by score holder {@link Relay#IGT}.
    private static ScoreAccess igtScore;
    
    /// The team containing all spectators.
    private static Team spectator;
    
    static {
        IGT = new ScoreHolder() {
            
            @Override
            public String getNameForScoreboard() { return "igt"; }
            
            @Override
            public Text getDisplayName() {
                return Text.literal("IGT " + getCountdown(igt)).withColor(0x00ffff);
            }
            
        };
        RTA = new ScoreHolder() {
            
            @Override
            public String getNameForScoreboard() { return "rta"; }
            
            @Override
            public Text getDisplayName() {
                return Text.literal("RTA " + getCountdown(rta)).withColor(0xff00ff);
            }
            
        };
    }
    
    public static State getCurrentState() { return state; }
    
    /// Initializes basic fields and reads saved data.
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
    
    /// Saves the current state of the relay speedrun to the file referenced by the path {@link Relay#RELAY}.
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
    
    /// Handles new players joining.
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
    
    /// Starts the relay speedrun.
    public static void start() {
        players.forEach(Relay::makeSpectator);
        takeOver(players.getFirst());
        state = State.RUNNING;
        LOGGER.info("Relay started");
    }
    
    /// Pauses the relay speedrun.
    public static void pause() {
        state = State.PAUSING;
        LOGGER.info("Relay paused");
    }
    
    /// Resumes the relay speedrun.
    public static void resume() {
        state = State.RUNNING;
        LOGGER.info("Relay resumed");
    }
    
    /// Stops the relay speedrun.
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
    
    /// Called every tick.
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
        ServerPlayNetworkHandler networkHandler = next.networkHandler;
        switch (timer) {
            case 100,
                 80,
                 60,
                 40,
                 20 -> networkHandler.sendPacket(new PlaySoundS2CPacket(
              SoundEvents.BLOCK_NOTE_BLOCK_HARP,
              SoundCategory.MASTER,
              0d,
              320d,
              0d,
              10f,
              1f,
              0L));
            case 0 -> {
                networkHandler.sendPacket(new PlaySoundS2CPacket(
                  SoundEvents.BLOCK_NOTE_BLOCK_HARP,
                  SoundCategory.MASTER,
                  0d,
                  320d,
                  0d,
                  10f,
                  2f,
                  0L));
                takeOver(next);
            }
        }
        timer--;
        igt++;
        updateScore(igtScore);
    }
    
    /**
     * Updates a scoreboard score to sync its display text.
     *
     * @param score The score to update.
     */
    private static void updateScore(ScoreAccess score) { score.setScore(score.getScore()); }
    
    /// Returns the time the current player has left in the form of a displayed string.
    private static String getCurrentPlayerCountdown() { return getCountdown(timer); }
    
    /// Returns the time until it is the turn for {@code player} in the form of a displayed string.
    private static String getTotalCountdown(ServerPlayerEntity player) {
        int queuePos = players.indexOf(player) - players.indexOf(current) - 1;
        if (queuePos < 0) queuePos += players.size();
        return getCountdown(timer + queuePos * countdown);
    }
    
    /// Returns a display string for {@code ticks} in the form of "HH:MM:SS.mm".
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
    
    /// Returns the next player supposed to take over the speedrunner.
    private static ServerPlayerEntity getNextPlayer() {
        return players.get((players.indexOf(current) + 1) % players.size());
    }
    
    /// Makes {@code player} a spectator.
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
    
    /// Makes {@code player} take over the current player.
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
        inheritFallDistance(player);
        inheritPortalCooldown(player);
        inheritHP(player);
        inheritHunger(player);
        inheritAir(player);
        inheritXp(player);
        inheritStatusEffects(player);
        inheritSelectedHotbarSlot(player);
        inheritInventory(player);
        inheritEnderChestInventory(player);
        inheritSpawnpoint(player);
        replaceShoulders(player);
        inheritSculkShriekerWarnings(player);
        iterateEntities(player);
        if (!replaceRiding(player)) tp(player);
        makeSpectator(current);
        
        current = player;
    }
    
    /// Removes {@code player} from the spectators' team, and add the current player in.
    private static void replaceTeam(ServerPlayerEntity player) {
        removeScoreHolderFromTeam(player, spectator);
        addScoreHolderToTeam(current, spectator);
    }
    
    /// Inherits the fall distance.
    private static void inheritFallDistance(ServerPlayerEntity player) { player.fallDistance = current.fallDistance; }
    
    /// Inherits the nether portal cooldown.
    private static void inheritPortalCooldown(
      ServerPlayerEntity player) { player.setPortalCooldown(current.getPortalCooldown()); }
    
    /// Inherits health.
    private static void inheritHP(ServerPlayerEntity player) { player.setHealth(current.getHealth()); }
    
    /// Inherits hunger.
    private static void inheritHunger(ServerPlayerEntity player) {
        HungerManager playerhm  = player.getHungerManager();
        HungerManager currenthm = current.getHungerManager();
        playerhm.setFoodLevel(currenthm.getFoodLevel());
        playerhm.setSaturationLevel(currenthm.getSaturationLevel());
        ((HungerManagerMixin) playerhm).setExhaustion(((HungerManagerMixin) currenthm).getExhaustion());
    }
    
    /// Inherits the oxygen level.
    private static void inheritAir(ServerPlayerEntity player) { player.setAir(current.getAir()); }
    
    /// Inherits experience level.
    private static void inheritXp(ServerPlayerEntity player) {
        player.experienceLevel    = current.experienceLevel;
        player.experienceProgress = current.experienceProgress;
        player.totalExperience    = current.totalExperience;
    }
    
    /// Inherits the status effects.
    private static void inheritStatusEffects(ServerPlayerEntity player) {
        clearEffects(player);
        for (StatusEffectInstance effect : current.getStatusEffects()) player.addStatusEffect(effect);
    }
    
    /// Inherits the selected hotbar slot.
    private static void inheritSelectedHotbarSlot(ServerPlayerEntity player) {
        player.getInventory().setSelectedSlot(current.getInventory().getSelectedSlot());
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().getSelectedSlot()));
    }
    
    /// Inherits the inventory.
    private static void inheritInventory(ServerPlayerEntity player) {
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
    
    /// Inherits the ender chest inventory.
    private static void inheritEnderChestInventory(ServerPlayerEntity player) {
        EnderChestInventory playereci  = player.getEnderChestInventory();
        EnderChestInventory currenteci = current.getEnderChestInventory();
        
        for (int i = 0; i < playereci.size(); i++) {
            playereci.setStack(i, currenteci.getStack(i));
            currenteci.setStack(i, ItemStack.EMPTY);
        }
    }
    
    /// Inherits the spawnpoint.
    private static void inheritSpawnpoint(ServerPlayerEntity player) { player.setSpawnPointFrom(current); }
    
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
    
    /// Inherits data of sculk shrieker warnings.
    private static void inheritSculkShriekerWarnings(ServerPlayerEntity player) {
        current.getSculkShriekerWarningManager().ifPresent(sswm -> {
            ((SculkShriekerWarningManagerMixin) player
              .getSculkShriekerWarningManager()
              .orElseThrow()).relay_speedrun$copy(current.getSculkShriekerWarningManager().orElseThrow());
            sswm.reset();
        });
    }
    
    /**
     * <li>Makes every mob targeting the current player target {@code player}.</li>
     * <li>Makes every pet of the current player be pet of {@code player}.</li>
     * <li>Makes the owner of every projectile produced by the current player to be {@code player}.</li>
     * <li>Makes the owner of every tnt entity produced by the current player to be {@code player}.</li>
     * <li>Makes the owner of every item entity dropped by the current player to be {@code player}.</li>
     */
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
                    UUID owner = itemmx.getOwnerUuid();
                    if (owner != null && owner.equals(current.getUuid())) itemmx.setOwnerUuid(currentPlayerUuid);
                }
                default -> { }
            }
    }
    
    /// Makes {@code player} ride whatever the current player is riding, if they are riding anything.
    private static boolean replaceRiding(ServerPlayerEntity player) {
        Entity vehicle = current.getVehicle();
        if (vehicle != null) {
            current.stopRiding();
            player.startRiding(vehicle);
            return true;
        }
        return false;
    }
    
    /// Teleports {@code player} to the current player, inheriting their velocity.
    private static void tp(ServerPlayerEntity player) {
        ServerPlayNetworkHandlerMixin handler = (ServerPlayNetworkHandlerMixin) current.networkHandler;
        player.teleportTo(new TeleportTarget(
          current.getEntityWorld(), current.getEntityPos(), new Vec3d(
          current.getX() - handler.getLastTickX(),
          current.getY() - handler.getLastTickY(),
          current.getZ() - handler.getLastTickZ()), current.getYaw(), current.getPitch(), TeleportTarget.NO_OP));
    }
    
    /// Clear the status effects of {@code player}.
    private static void clearEffects(ServerPlayerEntity player) {
        new ArrayList<>(player.getStatusEffects()).forEach(effect -> player.removeStatusEffect(effect.getEffectType()));
    }
    
    /// Adds {@code scoreHolder} to {@code team}.
    private static void addScoreHolderToTeam(ScoreHolder scoreHolder, Team team) {
        String name = scoreHolder.getNameForScoreboard();
        scoreboard.addScoreHolderToTeam(name, team);
    }
    
    /// Safely removes {@code scoreHolder} from {@code team}.
    private static void removeScoreHolderFromTeam(ScoreHolder scoreHolder, Team team) {
        String name = scoreHolder.getNameForScoreboard();
        if (scoreboard.getScoreHolderTeam(name) == team) scoreboard.removeScoreHolderFromTeam(name, team);
    }
    
    /// An indicator for different stages throughout a relay speedrun.
    public enum State {
        
        /// Before the relay speedrun has started or after it has been manually stopped.
        BEFORE_START,
        /// During the speedrun.
        RUNNING,
        /// When the speedrun is paused can be manually resumed.
        PAUSING,
        /// When the speedrun is paused forcefully and cannot be manually resumed because the current player is not in the server.
        FORCED_PAUSING,
        /// After the dragon has been killed and the relay speedrun has ended.
        ENDED;
        
        @Override
        public String toString() { return name().toLowerCase(Locale.ROOT); }
        
        /// Returns {@code true} when {@link Relay#tick} is supposed to run.
        public boolean isTicking() { return this != BEFORE_START && this != ENDED; }
        
        /// Returns {@code true} when the relay speedrun is paused.
        public boolean isPaused() { return this == PAUSING || this == FORCED_PAUSING; }
        
        public static State parse(String str) {
            for (State state : values()) if (state.toString().equals(str)) return state;
            return null;
        }
        
    }
    
}

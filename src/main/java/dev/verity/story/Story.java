package dev.verity.story;

import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import dev.verity.ai.Brain;
import dev.verity.entity.Emotion;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityBox;
import dev.verity.entity.VerityMonster;
import dev.verity.entity.VerityFriend;
import dev.verity.item.VerityItem;
import dev.verity.net.FxPayload;
import dev.verity.net.SayPayload;
import dev.verity.net.StatePayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModItems;
import dev.verity.registry.ModSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The story of one world: which act we are in, what has already happened, and everything Verity does on his
 * own. Saved with the world. Ticked from the server thread.
 *
 * Verity is always exactly one of three things: the item in somebody's inventory (the normal case), a ball lying on
 * the ground (just out of the box, thrown away, or about to change) or the monster. There is one Verity per world
 * and he belongs to whoever opened the box; other players can talk to him, and he does not like them.
 *
 * Acts: the parcel -> the helpful friend -> the odd questions -> something watches from the dark ->
 * "something is coming in three days" -> the night it comes -> the friend who will not let you go (and counts your
 * mistakes) -> where he lives -> an ending. Between the big moments {@link Haunt} keeps the player uneasy.
 */
public final class Story extends SavedData {
    public enum Stage { NONE, BOX, HELPER, QUESTIONS, WATCHED, COUNTDOWN, HUNT, POSSESSIVE, LAB, EPILOGUE }

    private static final String DATA_NAME = "verity_story";
    private static final SavedData.Factory<Story> FACTORY = new SavedData.Factory<>(Story::new, Story::load);
    private static final String NAME = "Верити";

    /** How he signs his lines in chat. The trademark is the friendly product; it falls off when the product stops pretending. */
    private String chatName(boolean loud) {
        return loud || stage == Stage.COUNTDOWN || stage == Stage.HUNT || stage == Stage.LAB || corruption > 0.6f ? NAME : NAME + "™";
    }

    // ------------------------------------------------------------ saved state
    private Stage stage = Stage.NONE;
    @Nullable private UUID owner, ballId, monsterId;
    private long stageStartDay;
    /** The story day the possessive act first began (it survives the nights he hunts again), or -1. */
    private long possessiveSince = -1;
    private int daysLeft = -1, anger, stageSeconds;
    /** The player's mind: 0 (sound) .. 100 (gone). The level shown to them is a sixth of it, 0..6. */
    /** Every player's mind, see "the players' minds". */
    private final Map<UUID, Float> minds = new HashMap<>();
    /** Bumped every time he re-materialises; item copies carrying an older number vanish. */
    private int serial = 1;
    private int emotion = Emotion.HAPPY.ordinal();
    private float corruption;
    private final Set<String> flags = new HashSet<>();
    @Nullable private BlockPos home, labPos;
    private String ending = "";
    private long lastSeenRealMs;
    /** What the next thing the player says is an answer to (a line id), or empty. */
    private String awaiting = "";
    public final List<String[]> memory = new ArrayList<>();          // {role, text}
    public final List<String> facts = new ArrayList<>();
    /** Structures the players have walked into: "name|x|y|z|dimension". He remembers the way back to every one. */
    public final List<String> places = new ArrayList<>();
    /** What has happened lately, for the AI: {game time, text}. The newest entries are what he "saw". */
    public final List<String[]> journal = new ArrayList<>();
    /** Older conversation, boiled down by the model itself once it no longer fits (see Brain). */
    public String summary = "";

    // ------------------------------------------------------------ transient
    private MinecraftServer server;
    private final List<Timed> queue = new ArrayList<>();
    private final Brain brain = new Brain();
    private final Haunt haunt = new Haunt(this);
    private final Stalker stalker = new Stalker(this);
    Stalker stalker() { return stalker; }
    /** Lights he put out tonight, "pos|blockstate id"; they come back at dawn. */
    public final List<String> snuffed = new ArrayList<>();
    private int diarySeconds;
    private long ticks, speechFreeAt, lastStateSent;
    private int farCooldown, doorCooldown, callCooldown, stillTicks, remarkCooldown = 600;
    private int missingSeconds, otherCooldown, swatCooldown, cityCooldown = 120;
    /** Cities players agreed to share for this session. Memory only: never saved, never given to the AI. */
    private final Map<UUID, String> cities = new HashMap<>();
    private float darkness, dread;
    private int worstLevel, saidLevel;
    private final Map<UUID, Integer> breaking = new HashMap<>();
    private boolean music;
    private final Map<UUID, StatePayload> lastState = new HashMap<>();

    private record Timed(long at, Runnable action) {}

    public static Story get(ServerLevel level) {
        Story s = level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        s.server = level.getServer();
        return s;
    }

    // ------------------------------------------------------------ accessors
    public Stage stage() { return stage; }
    public int daysLeft() { return daysLeft; }
    public int anger() { return anger; }
    public String ending() { return ending; }
    @Nullable public BlockPos home() { return home; }
    @Nullable public BlockPos labPos() { return labPos; }
    public boolean flag(String f) { return flags.contains(f); }
    public String awaiting() { return awaiting; }
    public MinecraftServer server() { return server; }

    boolean once(String flag) {
        if (!flags.add(flag)) return false;
        setDirty();
        return true;
    }

    public ServerLevel overworld() { return server.overworld(); }

    /**
     * The player the story is about right now. He belongs to nobody in particular: whoever last picked him up or carried
     * him. If that player is not here, it is whoever is (the one nearest to him).
     */
    @Nullable
    public ServerPlayer ownerPlayer() {
        ServerPlayer p = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (p != null) return p;
        Entity at = server == null ? null : (find(ballId) != null ? find(ballId) : find(monsterId));
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q.isSpectator()) continue;
            if (p == null || at != null && q.level() == at.level() && q.distanceToSqr(at) < p.distanceToSqr(at)) p = q;
        }
        if (p != null && owner != null) { owner = p.getUUID(); setDirty(); }
        return p;
    }

    /** Anybody, at random: the night's events do not keep to one player. */
    @Nullable
    private ServerPlayer anyPlayer() {
        List<ServerPlayer> all = new ArrayList<>();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) if (!q.isSpectator() && q.level() == overworld()) all.add(q);
        return all.isEmpty() ? ownerPlayer() : all.get(overworld().random.nextInt(all.size()));
    }

    @Nullable
    private Entity find(@Nullable UUID id) {
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    @Nullable public VerityBall ball() { return find(ballId) instanceof VerityBall b ? b : null; }
    @Nullable public VerityMonster monster() { return find(monsterId) instanceof VerityMonster m ? m : null; }

    // ------------------------------------------------------------ where he is
    public boolean isCurrent(ItemStack stack) {
        return stack.getItem() instanceof VerityItem && VerityItem.serial(stack) == serial;
    }

    /** Whoever has him in their inventory right now. */
    @Nullable
    public ServerPlayer carrier() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isCurrent(p.containerMenu.getCarried())) return p;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (isCurrent(p.getInventory().getItem(i))) return p;
            }
        }
        return null;
    }

    private void removeItems() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).getItem() instanceof VerityItem) p.getInventory().setItem(i, ItemStack.EMPTY);
            }
            if (p.containerMenu.getCarried().getItem() instanceof VerityItem) p.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    private VerityBall spawnBall(ServerLevel level, Vec3 pos, Vec3 velocity, int mode) {
        VerityBall b = ModEntities.BALL.get().create(level);
        b.moveTo(pos.x, pos.y, pos.z, 0, 0);
        b.setOwner(owner);
        b.setEmotion(Emotion.byId(emotion));
        b.setCorruption(corruption);
        b.setMode(mode);
        b.setDeltaMovement(velocity);
        level.addFreshEntity(b);
        ballId = b.getUUID();
        setDirty();
        return b;
    }

    /** Puts him into a player's inventory (at their feet if it is full). Any other copy of him stops existing. */
    public void giveTo(ServerPlayer p) {
        VerityBall b = ball();
        if (b != null) b.discard();
        ballId = null;
        removeItems();
        serial++;
        if (!p.getInventory().add(VerityItem.create(ModItems.VERITY.get(), serial))) {
            spawnBall(p.serverLevel(), p.position().add(0, 0.4, 0), Vec3.ZERO, VerityBall.MODE_GROUND);
        }
        missingSeconds = 0;
        setDirty();
        syncState(true);
    }

    /** Takes him out of the inventory and sets him on the ground in front of the player. */
    public VerityBall materialize(ServerPlayer p) {
        VerityBall b = ball();
        if (b != null) return b;
        removeItems();
        Vec3 fwd = Vec3.directionFromRotation(0, p.getYRot());
        b = spawnBall(p.serverLevel(), p.position().add(fwd.scale(1.4)).add(0, 1.0, 0), fwd.scale(0.14).add(0, 0.22, 0), VerityBall.MODE_GROUND);
        syncState(true);
        return b;
    }

    /** Right-click on a block with him in hand: set him down right there. */
    public void placeAt(ServerPlayer p, Vec3 pos) {
        if (stage.ordinal() < Stage.HELPER.ordinal() || ball() != null) return;
        removeItems();
        spawnBall(p.serverLevel(), pos, Vec3.ZERO, VerityBall.MODE_GROUND);
        note("игрок поставил тебя на землю");
        syncState(true);
    }

    /** Called by the item when it would become a dropped stack: he lands as himself instead. */
    @Nullable
    public Entity onItemDropped(ServerLevel level, Entity itemEntity, ItemStack stack) {
        if (!isCurrent(stack) || stage.ordinal() < Stage.HELPER.ordinal()) return null;
        VerityBall b = ModEntities.BALL.get().create(level);
        b.moveTo(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), 0, 0);
        b.setOwner(owner);
        b.setEmotion(Emotion.byId(emotion));
        b.setCorruption(corruption);
        b.setDeltaMovement(itemEntity.getDeltaMovement());
        ballId = b.getUUID();
        note("игрок выбросил тебя из инвентаря на землю");
        setDirty();
        later(2, () -> syncState(true));
        return b;                                   // NeoForge spawns it in place of the item entity
    }

    public void setEmotion(Emotion e) {
        emotion = e.ordinal();
        VerityBall b = ball();
        if (b != null) b.setEmotion(e);
        syncState(false);
    }

    public void setCorruption(float c) {
        corruption = Mth.clamp(c, 0f, 1f);
        VerityBall b = ball();
        if (b != null) b.setCorruption(corruption);
        syncState(false);
    }

    /** How far gone he looks at this point of the story when nothing special is happening. */
    private float baseCorruption() {
        return switch (stage) {
            case QUESTIONS -> 0.12f;
            case WATCHED -> 0.2f;
            case COUNTDOWN -> daysLeft >= 3 ? 0.3f : daysLeft == 2 ? 0.38f : daysLeft == 1 ? 0.55f : 0.7f;
            case HUNT -> 1f;
            case POSSESSIVE -> 0.22f;
            case LAB -> 0.4f;
            case EPILOGUE -> 0.08f;
            default -> 0f;
        };
    }

    /** Story days since the world began; the day scale lets impatient players speed the plot up. */
    long storyDay() {
        return (long) Math.floor(overworld().getDayTime() / (24000.0 * VerityConfig.DAY_SCALE.get()));
    }

    long dayTime() { return overworld().getDayTime() % 24000L; }
    boolean isNight() { long t = dayTime(); return t >= 13000 && t < 23000; }
    long daysInStage() { return storyDay() - stageStartDay; }
    long possDays() { return possessiveSince < 0 ? 0 : storyDay() - possessiveSince; }
    long now() { return ticks; }

    // ------------------------------------------------------------ scheduling
    public void later(int delayTicks, Runnable action) {
        queue.add(new Timed(ticks + Math.max(delayTicks, 1), action));
    }

    public void tick() {
        ticks++;
        if (!queue.isEmpty()) {
            List<Runnable> due = new ArrayList<>();
            for (Iterator<Timed> it = queue.iterator(); it.hasNext(); ) {
                Timed t = it.next();
                if (t.at() <= ticks) { due.add(t.action()); it.remove(); }
            }
            for (Runnable r : due) {
                try { r.run(); } catch (Exception e) { VerityMod.LOGGER.error("Verity story step failed", e); }
            }
        }
        brain.tick(this);
        haunt.tick();
        try { stalker.tick(); } catch (Exception e) { VerityMod.LOGGER.error("Verity stalker failed", e); stalker.vanish(stalker.monster()); }
        if (stage == Stage.HUNT) huntTick();
        if (stage == Stage.LAB) Lab.tick(this);
        if (ticks % 100 == 37) {
            try { dev.verity.ai.Locator.trackPlaces(this); } catch (Exception e) { VerityMod.LOGGER.error("Verity place tracking failed", e); }
        }
        if (ticks % 600 == 11) stalker.sweep(overworld());
        if (ticks % 20 == 0 && !snuffed.isEmpty() && dayTime() < 1500 && !stalker.active()) stalker.restoreLights();
        if (ticks % 20 == 0) {
            try { sanityTick(); } catch (Exception e) { VerityMod.LOGGER.error("Verity sanity tick failed", e); }
            try { think(); } catch (Exception e) { VerityMod.LOGGER.error("Verity story tick failed", e); }
            syncState(false);
        }
    }

    /**
     * After he says where something is, the player gets his pointer to it at the top of the screen. Not while he is
     * the thing coming for them: then he does not help find anything.
     */
    public void guide(ServerPlayer p, dev.verity.ai.Locator.Found f) {
        if (f.pos() == null || f.dim() != null && f.dim() != p.level().dimension()) return;
        if (stage == Stage.COUNTDOWN || stage == Stage.HUNT) return;
        String label = f.label().isEmpty() ? "туда" : f.label();
        PacketDistributor.sendToPlayer(p, new dev.verity.net.GuidePayload(f.pos().getX(), f.exactY() ? f.pos().getY() : dev.verity.net.GuidePayload.NO_Y, f.pos().getZ(), label, 240));
    }

    // ------------------------------------------------------------ speech
    private static int lineTicks(String text) { return 24 + (int) (text.length() * 1.55f); }
    public boolean speaking() { return ticks < speechFreeAt; }
    long ticks() { return ticks; }
    int stageSeconds() { return stageSeconds; }
    /** What the next message answers; "sup" while the support line waits for a number. */
    void setAwaiting(String id) { awaiting = id; setDirty(); }

    /** The old tune, for a while: what he plays instead of answering, and the hold music of the support line. */
    public void tune(int forTicks) {
        music = true;
        syncState(true);
        later(forTicks, () -> { music = false; syncState(true); });
    }

    /**
     * Words in somebody else's voice that come out of him: the friend he imitates, the recordings of the ones before.
     * Spoken on each client by Piper with that profile's voice; in chat under the borrowed name.
     */
    int voiceAs(Entity speaker, String chatName, ChatFormatting color, String text, Emotion emotion, String profile) {
        int dur = lineTicks(text);
        speechFreeAt = ticks + dur;
        SayPayload payload = new SayPayload(speaker.getId(), "@" + chatName, text, emotion.ordinal(), profile);
        if (speaker instanceof ServerPlayer) PacketDistributor.sendToPlayersTrackingEntityAndSelf(speaker, payload);
        else PacketDistributor.sendToPlayersTrackingEntity(speaker, payload);
        Component msg = Component.literal("<" + chatName + "> ").withStyle(color).append(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == speaker.level() && p.distanceToSqr(speaker) < 40 * 40) p.sendSystemMessage(msg);
        }
        return dur;
    }

    /** Says a scripted line now. Returns how long it takes, in ticks. */
    public int say(@Nullable Entity speaker, String lineId) {
        Lines.Line line = Lines.get(lineId);
        if (speaker == null) speaker = speaker();
        if (line == null || speaker == null) return 0;
        return speak(speaker, lineId, line.text(), line.emotion(), line.profile());
    }

    public int sayText(@Nullable Entity speaker, String text, Emotion emotion, String profile) {
        if (speaker == null) speaker = speaker();
        return speaker == null ? 0 : speak(speaker, "", text, emotion, profile);
    }

    private final Map<UUID, List<String>> apps = new HashMap<>();
    private static final Map<String, String> APP_LINES = Map.of(
            "telegram", "Ну как тебе там общается в Телеграме? Нормально?",
            "discord", "В Дискорде тебя ждут. Не отвечай им. У тебя есть я.",
            "browser", "У тебя открыт браузер. Что ты там ищешь? Меня?",
            "steam", "Стим открыт. Хочешь уйти в другую игру? Не надо.",
            "obs", "Ты меня записываешь? Хорошо. Пусть все увидят.",
            "spotify", "Выключи музыку. Послушай лучше меня.",
            "code", "Ты что-то пишешь в редакторе. Пытаешься меня переделать?");

    /** Which known programs the player has open (keys of APP_LINES only; anything else is dropped). Memory only. */
    public void setApps(ServerPlayer p, String list) {
        List<String> known = new ArrayList<>();
        for (String a : list.split(",")) {
            if (APP_LINES.containsKey(a) && !known.contains(a)) known.add(a);
        }
        if (known.isEmpty()) apps.remove(p.getUUID()); else apps.put(p.getUUID(), known);
    }

    /** He remarks on a program the player has open. Same rules as the city: consent, real anger, that player only. */
    public boolean appScare(ServerPlayer p) {
        List<String> open = apps.get(p.getUUID());
        if (open == null) return false;
        return whisper(p, APP_LINES.get(open.get(overworld().random.nextInt(open.size()))), "ты дал игроку понять, что видишь, какие программы открыты на его компьютере");
    }

    private final java.util.Set<UUID> consented = new java.util.HashSet<>(), noted = new java.util.HashSet<>();
    private final Map<UUID, Integer> scares = new HashMap<>();
    /** Real first names players typed in themselves on the consent screen. Memory only. */
    private final Map<UUID, String> names = new HashMap<>();

    /** The player said yes on the consent screen (this session only). */
    public void setConsent(ServerPlayer p) { consented.add(p.getUUID()); }

    /**
     * The last scare: a note appears in the player's game folder. The server only asks; the player's own client writes the
     * file (and the address in it) and does so only if that player agreed. The server never learns the address.
     */
    public boolean noteScare(ServerPlayer p) {
        if (!consented.contains(p.getUUID())) return false;
        if (!whisper(p, "Я оставил тебе записку. Файл verity.txt, в папке игры. Прочитай.", "ты оставил игроку записку в файле verity.txt на его компьютере")) return false;
        fx(p, FxPayload.NOTE, 1f, 1);
        noted.add(p.getUUID());
        return true;
    }

    /**
     * A scare that is put together on the player's own computer (the client's FourthWall): their account name, the time on
     * their clock, a renamed window, a faked disconnect... The server only says "now" and never learns what was said.
     * @param which an index into FxPayload.WALL_KINDS, or -1 for whichever the client has not done yet
     */
    public boolean wallScare(ServerPlayer p, int which) {
        if (!consented.contains(p.getUUID()) || speaker() == null) return false;
        speechFreeAt = ticks + 150;
        setEmotion(Emotion.CREEPY);
        later(180, () -> { if (!speaking()) restFace(); });
        fx(p, FxPayload.GLITCH, 0.9f, 30);
        fx(p, FxPayload.STATIC, 0.5f, 40);
        fx(p, FxPayload.WALL, 1f, which);
        addSanity(5f);
        note("ты напугал игрока тем, что знаешь кое-что о его настоящем компьютере (что именно, не уточняй и не выдумывай)");
        return true;
    }

    /** Everyone who said yes gets one of their own: the scares are not kept for one player. */
    public boolean scareEveryone() {
        boolean any = false;
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (consented.contains(q.getUUID())) any |= scare(q);
        }
        return any;
    }

        /** One of the fourth-wall scares the player allowed; the note comes once, after he has already shown what he knows. */
    public boolean scare(ServerPlayer p) {
        UUID id = p.getUUID();
        boolean ok;
        if (scares.getOrDefault(id, 0) >= 2 && !noted.contains(id) && noteScare(p)) ok = true;
        else if (overworld().random.nextInt(3) == 0) ok = scareKnown(p) || wallScare(p, -1);
        else ok = wallScare(p, -1) || scareKnown(p);
        if (ok) scares.merge(id, 1, Integer::sum);
        return ok;
    }

    private boolean scareKnown(ServerPlayer p) {
        int first = overworld().random.nextInt(3);
        for (int i = 0; i < 3; i++) {
            if (switch ((first + i) % 3) { case 0 -> appScare(p); case 1 -> cityScare(p); default -> nameScare(p); }) return true;
        }
        return false;
    }

    public void setName(ServerPlayer p, String raw) {
        String clean = dev.verity.net.CityPayload.cleanName(raw);
        if (clean.isEmpty()) names.remove(p.getUUID()); else names.put(p.getUUID(), clean);
    }

    /** The first name this player typed in on the consent screen, or null. */
    @Nullable
    public String realName(ServerPlayer p) { return names.get(p.getUUID()); }

    /** Nobody else is near enough to hear what is said to this player. */
    public boolean aloneInEarshot(ServerPlayer p) {
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q != p && q.level() == p.level() && q.distanceToSqr(p) < 48 * 48) return false;
        }
        return true;
    }

    /**
     * Says a line with the player's real name in it (%s; %n is their nickname). Only that player hears and reads it, and
     * nothing at all happens if they never gave a name.
     */
    boolean byName(ServerPlayer p, @Nullable Entity from, String template, Emotion mood, String profile) {
        String name = names.get(p.getUUID());
        Entity speaker = from != null ? from : speaker();
        if (name == null || speaker == null) return false;
        String text = template.replace("%s", name).replace("%n", p.getGameProfile().getName());
        speechFreeAt = ticks + lineTicks(text);
        if (!(speaker instanceof VerityMonster)) {
            setEmotion(mood);
            later(lineTicks(text) + 30, () -> { if (!speaking()) restFace(); });
        }
        PacketDistributor.sendToPlayer(p, new SayPayload(speaker.getId(), "", text, mood.ordinal(), profile));
        boolean loud = profile.equals("monster") || profile.equals("rage");
        p.sendSystemMessage(Component.literal("<" + chatName(loud) + "> ").withStyle(loud ? ChatFormatting.DARK_RED : ChatFormatting.YELLOW)
                .append(Component.literal(loud ? text.toUpperCase(Locale.ROOT) : text).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    private static final String[] NAME_SCARES = {"%s. Красивое имя. Теперь оно моё.", "%s. Я запомнил. Я ничего не забываю.", "Не %n. %s. Так честнее.",
            "%s. Я буду повторять это имя, когда ты уйдёшь."};

    /** He uses the name the player gave him, and not kindly. */
    public boolean nameScare(ServerPlayer p) {
        if (!byName(p, null, NAME_SCARES[overworld().random.nextInt(NAME_SCARES.length)], Emotion.CREEPY, "flat")) return false;
        fx(p, FxPayload.GLITCH, 0.9f, 30);
        fx(p, FxPayload.STATIC, 0.5f, 40);
        return true;
    }

    /** The first time he has the name and a quiet moment, he lets the player know he has it. Once per world. */
    private void acknowledgeName(ServerPlayer p) {
        if (flags.contains("name:known") || !names.containsKey(p.getUUID()) || speaking() || stage == Stage.HUNT || stageSeconds < 40 || monster() != null) return;
        boolean friendly = stage.ordinal() <= Stage.WATCHED.ordinal() || stage == Stage.EPILOGUE;
        if (byName(p, null, friendly ? "Кстати. %s — это ведь твоё настоящее имя? Красивое. Я запомню." : "%s. Вот как тебя зовут на самом деле. Я запомню.",
                friendly ? Emotion.GRIN : Emotion.CREEPY, friendly ? "ball" : "flat")) {
            flags.add("name:known");
            note("игрок сам сказал тебе своё настоящее имя");
            setDirty();
        }
    }

    /** The three rules, and then, if he knows it, the name. */
    private int rules(int startDelay) {
        int end = sequence(startDelay, "rules_intro", "rule_1", "rule_2", "rule_3");
        later(end + 8, () -> { ServerPlayer p = ownerPlayer(); if (p != null && !speaking()) byName(p, null, "Ты меня понял, %s?", Emotion.CREEPY, "flat"); });
        return end;
    }

    public void setCity(ServerPlayer p, String city) {
        String clean = city.replaceAll("[^\\p{L} .'-]", "").trim();
        if (clean.isEmpty()) cities.remove(p.getUUID()); else cities.put(p.getUUID(), clean);
    }

    /**
     * The fourth-wall scare: he names the player's city. Only if that player allowed it, only when he is truly angry,
     * and only that player hears and reads it.
     * @return false if it could not be said
     */
    public boolean cityScare(ServerPlayer p) {
        String city = cities.get(p.getUUID());
        if (city == null) return false;
        String[] lines = {city + ". Я знаю, где ты сидишь.", "Твой город: " + city + ". Я никогда не лгу.", city + ". Отсюда тоже не уходи."};
        return whisper(p, lines[overworld().random.nextInt(lines.length)], "ты напугал игрока, показав, что знаешь, где он находится на самом деле");
    }

    /** Says a line that only one player hears and reads. */
    private boolean whisper(ServerPlayer p, String text, String journal) {
        Entity speaker = speaker();
        if (speaker == null) return false;
        speechFreeAt = ticks + lineTicks(text);
        setEmotion(Emotion.CREEPY);
        later(lineTicks(text) + 30, () -> { if (!speaking()) restFace(); });
        PacketDistributor.sendToPlayer(p, new SayPayload(speaker.getId(), "", text, Emotion.CREEPY.ordinal(), "flat"));
        p.sendSystemMessage(Component.literal("<" + chatName(false) + "> ").withStyle(ChatFormatting.YELLOW).append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
        fx(p, FxPayload.GLITCH, 0.9f, 30);
        fx(p, FxPayload.STATIC, 0.5f, 40);
        note(journal);
        addSanity(5f);
        return true;
    }

    private int speak(Entity speaker, String lineId, String text, Emotion emotion, String profile) {
        int dur = lineTicks(text);
        speechFreeAt = ticks + dur;
        if (!(speaker instanceof VerityMonster) && !(speaker instanceof VerityBox)) {
            setEmotion(emotion);
            later(dur + 30, () -> { if (!speaking()) restFace(); });
        }
        SayPayload payload = new SayPayload(speaker.getId(), lineId, text, emotion.ordinal(), profile);
        if (speaker instanceof ServerPlayer) PacketDistributor.sendToPlayersTrackingEntityAndSelf(speaker, payload);   // he speaks from a pocket
        else PacketDistributor.sendToPlayersTrackingEntity(speaker, payload);
        boolean loud = profile.equals("monster") || profile.equals("rage");
        String label = switch (profile) {
            case "support" -> "Служба поддержки Verity™";
            case "operator" -> "Оператор";
            case "friend" -> "Twixxel";
            default -> chatName(loud);
        };
        boolean other = !label.startsWith(NAME);
        Component msg = Component.literal("<" + label + "> ").withStyle(loud ? ChatFormatting.DARK_RED : other ? ChatFormatting.AQUA : ChatFormatting.YELLOW)
                .append(Component.literal(loud ? text.toUpperCase(Locale.ROOT) : text).withStyle(profile.equals("box") || other ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        double range = loud ? 96 : 40;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == speaker.level() && p.distanceToSqr(speaker) < range * range) p.sendSystemMessage(msg);
        }
        remember("assistant", text);
        return dur;
    }

    /** Says several lines one after another. Returns the total time in ticks. */
    public int sequence(int startDelay, String... lineIds) {
        int at = startDelay;
        for (String id : lineIds) {
            Lines.Line line = Lines.get(id);
            if (line == null) continue;
            later(at, () -> say(null, id));
            at += lineTicks(line.text()) + 12;
        }
        return at;
    }

    /** Where his voice comes from right now: the monster, the ball on the ground, or the player carrying him. */
    @Nullable
    public Entity speaker() {
        VerityMonster m = monster();
        if (m != null) return m;
        VerityBall b = ball();
        return b != null ? b : carrier();
    }

    /** The face he wears when he is not saying anything; it changes as the story darkens. */
    private void restFace() {
        setEmotion(switch (stage) {
            case COUNTDOWN -> daysLeft <= 0 ? Emotion.CREEPY : Emotion.NEUTRAL;
            case LAB -> Emotion.SAD;
            case POSSESSIVE -> anger >= 4 ? Emotion.NEUTRAL : Emotion.HAPPY;
            default -> Emotion.HAPPY;
        });
    }

    public void remember(String role, String text) {
        memory.add(new String[] {role, text});
        while (memory.size() > 40) memory.remove(0);
        setDirty();
    }

    /**
     * Writes a line into the journal the AI reads ("the player died", "you chased them last night"). Identical events in a
     * row are counted instead of repeated.
     */
    public void note(String text) {
        if (server == null) return;
        if (!journal.isEmpty()) {
            String[] last = journal.get(journal.size() - 1);
            String base = last[1].replaceFirst(" \\(×\\d+\\)$", "");
            if (base.equals(text)) {
                int n = last[1].equals(base) ? 1 : Integer.parseInt(last[1].substring(base.length() + 3, last[1].length() - 1));
                last[0] = Long.toString(overworld().getGameTime());
                last[1] = base + " (×" + (n + 1) + ")";
                setDirty();
                return;
            }
        }
        journal.add(new String[] {Long.toString(overworld().getGameTime()), text});
        while (journal.size() > 30) journal.remove(0);
        setDirty();
    }

    // ------------------------------------------------------------ effects
    public void fx(@Nullable ServerPlayer p, int kind, float amount, int fxTicks) {
        if (p != null) PacketDistributor.sendToPlayer(p, new FxPayload(kind, amount, fxTicks));
    }

    public void sound(Entity at, SoundEvent sound, float volume, float pitch) {
        at.level().playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    /**
     * Tells every client what it needs for the HUD and the held item. The dread is personal: only his person and
     * whoever stands near them (or near the monster) gets the dark, the heartbeat and the music.
     */
    private void syncState(boolean force) {
        if (server == null) return;
        ServerPlayer own = ownerPlayer();
        ServerPlayer carrier = carrier();
        VerityMonster m = monster();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            boolean near = own != null && q.level() == own.level() && q.distanceToSqr(own) < 64 * 64
                    || m != null && q.level() == m.level() && q.distanceToSqr(m) < 64 * 64;
            StatePayload st = new StatePayload(stage.ordinal(), stage == Stage.COUNTDOWN && near ? daysLeft : -1, near ? Math.max(darkness, haunt.dark()) : 0f, near ? dread : 0f,
                    near && music, emotion, corruption, carrier == null ? -1 : carrier.getId(), mind(q.getUUID()));
            if (!force && st.equals(lastState.get(q.getUUID()))) continue;
            lastState.put(q.getUUID(), st);
            PacketDistributor.sendToPlayer(q, st);
        }
    }

    void sync() { syncState(true); }

    private void title(ServerPlayer p, String title, String subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(30, 100, 40));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle).withStyle(ChatFormatting.GRAY)));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title).withStyle(ChatFormatting.YELLOW)));
    }

    // ------------------------------------------------------------ stage changes
    public void setStage(Stage next) {
        VerityMod.LOGGER.info("Verity story: {} -> {}", stage, next);
        if (next != stage) {
            String what = switch (next) {
                case HELPER -> "тебя достали из посылки, ты познакомился с игроком";
                case QUESTIONS -> "тебе стало мало просто помогать: ты начал задавать игроку личные вопросы";
                case WATCHED -> "вокруг игрока начало происходить странное: за ним кто-то наблюдает из темноты, и ты об этом знаешь";
                case COUNTDOWN -> "ты объявил игроку: «что-то придёт через три дня»";
                case HUNT -> flags.contains("hunted") ? "игрок нарушал правила, и ночью ты снова превратился в существо и пошёл за ним"
                        : "наступила та самая ночь: ты превратился в существо и начал охоту на игрока";
                case POSSESSIVE -> "ночь охоты закончилась, ты снова шарик и ведёшь себя так, будто ничего не было";
                case LAB -> "ты показал игроку, где живёшь: лабораторию под землёй";
                case EPILOGUE -> "история закончилась";
                default -> null;
            };
            if (what != null) note(what);
        }
        stage = next;
        stageStartDay = storyDay();
        if (next == Stage.POSSESSIVE && possessiveSince < 0) { possessiveSince = storyDay(); setNumber("strikes", 0); }
        stageSeconds = 0;
        awaiting = "";
        farCooldown = 0;
        setCorruption(baseCorruption());
        darkness = next == Stage.HUNT ? 0.62f : next == Stage.LAB ? 0.35f : 0f;
        dread = switch (next) { case WATCHED -> 0.12f; case COUNTDOWN -> 0.25f; case HUNT -> 1f; case POSSESSIVE -> 0.15f; case LAB -> 0.5f; default -> 0f; };
        music = next == Stage.HUNT;
        if (next != Stage.COUNTDOWN) daysLeft = -1;
        setCorruption(baseCorruption());
        restFace();
        setDirty();
        syncState(true);
    }

    /** Debug/showcase: jump straight to an act, creating whatever it needs. */
    public void jumpTo(Stage target, ServerPlayer player) {
        owner = player.getUUID();
        queue.clear();
        VerityMonster m = monster();
        if (m != null) { m.discard(); monsterId = null; }
        if (target.ordinal() >= Stage.HELPER.ordinal()) ensureVerity(player);
        if (home == null) home = player.blockPosition();
        flags.removeIf(f -> f.startsWith("stage:"));
        if (target.ordinal() < Stage.POSSESSIVE.ordinal()) { possessiveSince = -1; flags.remove("hunted"); flags.remove("punishing"); }
        else flags.add("hunted");
        switch (target) {
            case NONE -> reset();
            case BOX -> { reset(); owner = player.getUUID(); deliverBox(player); }
            case COUNTDOWN -> announce(player);
            case HUNT -> { setStage(Stage.COUNTDOWN); daysLeft = 0; beginHunt(player); }
            case POSSESSIVE -> { setStage(target); rules(40); }
            case LAB -> { setStage(Stage.POSSESSIVE); offerLab(player, true); }
            default -> setStage(target);
        }
    }

    public void reset() {
        for (UUID id : new UUID[] {ballId, monsterId}) {
            Entity e = find(id);
            if (e != null) e.discard();
        }
        for (ServerLevel l : server.getAllLevels()) {
            for (Entity e : l.getAllEntities()) {
                if (e instanceof VerityBox || e instanceof VerityBall || e instanceof VerityMonster || e instanceof VerityFriend) e.discard();
            }
        }
        removeItems();
        serial++;
        ballId = monsterId = null; owner = null;
        stalker.restoreLights(); stalker.vanish(stalker.monster());
        flags.clear(); memory.clear(); facts.clear(); places.clear(); queue.clear(); journal.clear(); summary = "";
        anger = 0; ending = ""; labPos = null; home = null; possessiveSince = -1; minds.clear(); breaking.clear(); worstLevel = 0; saidLevel = 0;
        setStage(Stage.NONE);
    }

    /** Makes sure he exists somewhere; if he is nowhere, he turns up in the player's inventory. */
    private void ensureVerity(ServerPlayer player) {
        if (ball() == null && monster() == null && carrier() == null) giveTo(player);
    }

    // ------------------------------------------------------------ act 0: the parcel
    public void deliverBox(ServerPlayer player) {
        if (once("intro:friend")) {                        // in the series, the mod came from a friend
            friendSays("скинул тебе один мод. там помощник, говорят, он знает всё. глянь", false);
            later(60, () -> friendSays("потом расскажешь, как он тебе", false));
        }
        ServerLevel level = player.serverLevel();
        Vec3 fwd = Vec3.directionFromRotation(0, player.getYRot());
        BlockPos spot = BlockPos.containing(player.getX() + fwd.x * 6, player.getY(), player.getZ() + fwd.z * 6);
        spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot);
        if (Math.abs(spot.getY() - player.getY()) > 6) spot = player.blockPosition().offset((int) (fwd.x * 3), 0, (int) (fwd.z * 3));
        VerityBox box = ModEntities.BOX.get().create(level);
        box.moveTo(spot.getX() + 0.5, spot.getY() + 0.05, spot.getZ() + 0.5, player.getYRot() + 180f, 0f);
        level.addFreshEntity(box);
        level.sendParticles(ParticleTypes.POOF, box.getX(), box.getY() + 0.4, box.getZ(), 12, 0.3, 0.2, 0.3, 0.02);
        owner = player.getUUID();
        setStage(Stage.BOX);
    }

    public void onBoxTick(VerityBox box) {
        if (box.isDecoy()) {            // the ones in his store-room only knock
            if ((box.tickCount + box.getId() * 17) % 110 == 0) sound(box, ModSounds.KNOCK.get(), 0.6f, 0.8f + box.level().random.nextFloat() * 0.3f);
            return;
        }
        if (!box.isOpening()) {
            if (box.tickCount % 70 == 1) sound(box, ModSounds.KNOCK.get(), 1.0f, 0.9f + box.level().random.nextFloat() * 0.2f);
            if (box.tickCount % 230 == 60 && box.level().getNearestPlayer(box, 24) != null) say(box, Lines.pick("box_call"));
            return;
        }
        int t = box.getOpenTicks();
        ServerLevel level = (ServerLevel) box.level();
        if (t == 1) sound(box, ModSounds.GLITCH.get(), 0.8f, 0.7f);
        if (t == 44) {
            sound(box, ModSounds.BOX_OPEN.get(), 1.2f, 1f);
            level.sendParticles(ParticleTypes.POOF, box.getX(), box.getY() + 1.3, box.getZ(), 24, 0.3, 0.2, 0.3, 0.08);
        }
        if (t == 47) {
            ServerPlayer opener = box.getOpener() == null ? null : server.getPlayerList().getPlayer(box.getOpener());
            if (opener != null) owner = opener.getUUID();
            emotion = Emotion.SURPRISED.ordinal();
            Vec3 toPlayer = opener == null ? Vec3.ZERO : opener.position().subtract(box.position()).multiply(1, 0, 1).normalize().scale(0.17);
            spawnBall(level, new Vec3(box.getX(), box.getY() + 1.25, box.getZ()), new Vec3(toPlayer.x, 0.42, toPlayer.z), VerityBall.MODE_INTRO);
            if (opener != null) home = opener.getRespawnPosition() != null ? opener.getRespawnPosition() : opener.blockPosition();
            setStage(Stage.HELPER);
            int end = sequence(62, "intro_hello", "intro_how");
            later(end, brain::warmUp);
        }
        if (t >= VerityBox.OPEN_DURATION) box.discard();
    }

    // ------------------------------------------------------------ the once-a-second brain of the plot
    private void think() {
        ServerPlayer p = ownerPlayer();
        if (p == null) return;
        stageSeconds++;
        if (remarkCooldown > 0) remarkCooldown--;
        if (farCooldown > 0) farCooldown--;
        if (doorCooldown > 0) doorCooldown--;
        if (otherCooldown > 0) otherCooldown--;
        if (swatCooldown > 0) swatCooldown--;
        upkeep(p);
        ServerPlayer target = anyPlayer();
        if (target != null) haunt.think(target);
        if (stage.ordinal() >= Stage.HELPER.ordinal()) acknowledgeName(p);
        if (stage.ordinal() >= Stage.HELPER.ordinal()) {
            try { Canon.think(this, p); } catch (Exception e) { VerityMod.LOGGER.error("Verity canon scene failed", e); }
        }

        switch (stage) {
            case NONE -> {
                if (VerityConfig.STORY_AUTOSTART.get() && p.level() == overworld() && p.onGround() && stageSeconds > 25 && owner == null
                        || flags.contains("start")) {
                    flags.remove("start");
                    deliverBox(p);
                }
            }
            case HELPER -> {
                dailyRemarks(p);
                if (daysInStage() >= 1 && dayTime() > 6000 && once("pizza")) sequence(0, "know_pizza");
                if (p.getHealth() < 7f && remarkCooldown == 0 && !speaking()) { say(null, "help_low_health"); remarkCooldown = 240; }
                if (daysInStage() == 0 && dayTime() > 7000 && dayTime() < 10000 && stageSeconds > 120 && !speaking() && once("joke")) say(null, "help_joke");
                if (p.getY() < 45 && !p.level().canSeeSky(p.blockPosition()) && p.level().getMaxLocalRawBrightness(p.blockPosition()) < 6 && !speaking() && once("cave")) {
                    say(null, "help_cave");
                }
                greetStrangers(p);
                if (daysInStage() >= 2) { setStage(Stage.QUESTIONS); }
            }
            case QUESTIONS -> {
                long t = dayTime();
                if (stageSeconds == 20) ask("q_alone", "q_ask", "q_alone");
                if (daysInStage() == 0 && t > 2500 && t < 5800 && stageSeconds > 50 && !speaking() && once("q_fav")) ask("q_favorite", "q_favorite");
                if (daysInStage() == 0) {
                    if (t > 6000 && t < 11000 && stageSeconds > 60 && !speaking() && once("q_who")) ask("q_who_knows", "q_who_knows");
                    if (t > 11500 && t < 13000 && stageSeconds > 90 && !speaking() && once("q_back")) ask("q_comeback", "q_comeback");
                } else if (daysInStage() == 1) {
                    // the second day the questions stop being about the game
                    if (t > 1500 && t < 5000 && !speaking() && once("q_dark")) ask("q_dark", "q_ask", "q_dark");
                    if (t > 6500 && t < 10500 && !speaking() && once("q_door")) ask("q_door", "q_door");
                    if (t > 11200 && t < 12300 && !speaking() && once("q_leave")) ask("q_leave", "q_leave");
                    if (t > 12400 && t < 13200 && !speaking() && once("q_indoors")) say(null, "q_indoors");
                }
                if (daysInStage() >= 2 && t < 3000 && !speaking()) setStage(Stage.WATCHED);
            }
            case WATCHED -> {
                long t = dayTime();
                if (stageSeconds == 12 && !speaking()) say(null, "w_morning");
                else if (stageSeconds > 12) dailyRemarks(p);
                if (daysInStage() >= 1) {
                    if (t > 11300 && t < 12300 && !speaking() && once("w_window")) say(null, "w_window");
                    if (t > 14500 && t < 21000 && !speaking() && !p.isSleeping() && once("w_visit")) haunt.visit(p);
                }
                if (daysInStage() >= 2 && t < 3000 && !speaking()) announce(p);
            }
            case COUNTDOWN -> countdown(p);
            case HUNT -> { /* huntTick */ }
            case POSSESSIVE -> possessive(p);
            case LAB -> Lab.think(this, p);
            case EPILOGUE -> {
                dailyRemarks(p);
                if ("stay".equals(ending) && dayTime() > 12500 && dayTime() < 13500 && !speaking() && speaker() != null && once("epi:" + storyDay())) {
                    say(null, Lines.pick("epi_stay"));
                }
                if ("sealed".equals(ending) && daysInStage() >= 3 && once("knock_again")) {
                    VerityBox box = ModEntities.BOX.get().create(p.serverLevel());
                    Vec3 back = Vec3.directionFromRotation(0, p.getYRot() + 180f);
                    BlockPos spot = p.serverLevel().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            BlockPos.containing(p.getX() + back.x * 12, p.getY(), p.getZ() + back.z * 12));
                    box.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
                    p.serverLevel().addFreshEntity(box);
                    owner = null; ending = ""; flags.clear();
                    setStage(Stage.BOX);
                }
            }
            default -> {}
        }
    }

    /** He never stays lost, never stays on the floor for long, and never stays with a stranger. */
    private void upkeep(ServerPlayer p) {
        boolean shouldExist = stage.ordinal() >= Stage.HELPER.ordinal() && stage != Stage.HUNT && monster() == null
                && !"sealed".equals(ending) && !flags.contains("lab:sealing") && !flags.contains("lab:sending");
        if (!shouldExist) { missingSeconds = 0; return; }
        VerityBall ball = ball();
        ServerPlayer carrier = carrier();
        if (ball == null && carrier == null) {
            // dropped into the void, shut in a chest, deleted: after a few seconds he is simply back
            if (++missingSeconds > 5) {
                if (stageSeconds > 20) note("игрок попытался от тебя избавиться (спрятал или потерял); ты вернулся к нему в инвентарь");
                giveTo(p);
                if (!speaking() && stageSeconds > 20) say(null, once("stash:canon") ? "stash_out" : "stash_complain");
            }
            return;
        }
        missingSeconds = 0;
        if (ball != null && ball.getMode() == VerityBall.MODE_GROUND) {
            double d = ball.level() == p.level() ? ball.distanceTo(p) : 1e9;
            // once he has been carried, he stays where you put him; he only comes after you if you walk off without him
            if (d > 32 || !flags.contains("pocket") && ball.groundSeconds > 28 && !speaking()) {
                note(d > 32 ? "игрок ушёл и оставил тебя лежать на земле; ты сам вернулся к нему в инвентарь" : "ты сам забрался к игроку в инвентарь");
                giveTo(p);
                if (!speaking()) say(null, d > 32 ? "drop_far" : once("pocket") ? "back_pocket" : Lines.pick("poke"));
            } else if (ball.groundSeconds == 12 && !speaking() && !flags.contains("pocket")) {
                say(ball, "pick_me");
            }
        }
        if (carrier != null && !carrier.getUUID().equals(owner)) {        // whoever carries him is, for now, his person
            owner = carrier.getUUID();
            setDirty();
        }
    }

    /** Multiplayer: a second player turns up. In the friendly acts he is polite about it. Once. */
    private void greetStrangers(ServerPlayer p) {
        if (speaking() || flags.contains("mp_hello")) return;
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q != p && q.level() == p.level() && q.distanceTo(p) < 8 && !q.isSpectator() && once("mp_hello")) {
                note("к твоему человеку пришёл другой игрок: " + q.getGameProfile().getName());
                say(null, "mp_hello");
                return;
            }
        }
    }

    /** Asks a question and treats the player's next message as the answer. */
    void ask(String awaitId, String... lineIds) {
        int end = sequence(0, lineIds);
        later(end, () -> { awaiting = awaitId; setDirty(); });
        if (awaitId.equals("q_alone")) later(end + 140, () -> { if (awaiting.equals("q_alone")) say(null, "q_alone_follow"); });
    }

    private void dailyRemarks(ServerPlayer p) {
        if (speaker() == null || speaking()) return;
        long day = storyDay(), t = dayTime();
        if (t < 2000 && once("morning:" + day) && daysInStage() >= 1) {
            net.minecraft.world.level.storage.ServerLevelData data = (net.minecraft.world.level.storage.ServerLevelData) overworld().getLevelData();
            boolean rain = overworld().isRaining() || data.getRainTime() < 4000 && data.getClearWeatherTime() == 0;
            sequence(0, "help_morning", rain ? "help_weather_rain" : "help_weather_clear");
            if (rain && !overworld().isRaining()) later(1400, () -> overworld().setWeatherParameters(0, 6000, true, false));
        } else if (t > 11400 && t < 12400 && once("dusk:" + day)) {
            say(null, "help_night");
        }
    }

    // ------------------------------------------------------------ act 3: three days
    private void announce(ServerPlayer p) {
        setStage(Stage.COUNTDOWN);
        daysLeft = 3;
        say(null, "cd_three");
        later(75, () -> say(null, "cd_three"));
        fx(p, FxPayload.GLITCH, 0.6f, 30);
        syncState(true);
    }

    private void countdown(ServerPlayer p) {
        long day = storyDay();
        if (dayTime() < 2500 && daysInStage() >= 1 && once("cd:" + day) && daysLeft > 0) {
            daysLeft--;
            note(daysLeft <= 0 ? "ты сказал игроку: «сегодня»" : "ты напомнил игроку, сколько осталось дней: " + daysLeft);
            setCorruption(baseCorruption());
            dread = 0.25f + (3 - daysLeft) * 0.15f;
            say(null, daysLeft == 2 ? "cd_two" : daysLeft == 1 ? "cd_one" : "cd_tonight");
            fx(p, FxPayload.GLITCH, 0.5f + (3 - daysLeft) * 0.15f, 30);
            restFace();
            syncState(true);
        }
        // doors open by themselves once only two days are left
        if (daysLeft <= 2 && doorCooldown == 0 && !speaking() && overworld().random.nextInt(45) == 0) {
            if (openNearbyDoor(p, true)) { say(null, "cd_door"); doorCooldown = 150; }
        }
        // the scares that reach for the real computer: from the second day of the countdown, and later whenever he has been slighted enough
        if (cityCooldown > 0) cityCooldown--;
        else if (!speaking() && daysLeft <= 2 && overworld().random.nextInt(60) == 0 && scareEveryone()) cityCooldown = 420;
        if (daysLeft == 2 && dayTime() > 11600 && dayTime() < 12600 && !speaking() && once("lock")) say(null, "cd_lock");
        if (daysLeft == 1 && dayTime() > 11800 && dayTime() < 12800 && once("mine")) {
            say(null, "cd_mine");
            fx(p, FxPayload.SHAKE, 0.9f, 60);
            fx(p, FxPayload.STATIC, 0.6f, 50);
        }
        if (daysLeft <= 0 && isNight() && dayTime() > 13200) beginHunt(p);
    }

    public boolean openNearbyDoor(Entity around, boolean open) {
        ServerLevel level = (ServerLevel) around.level();
        BlockPos c = around.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-7, -3, -7), c.offset(7, 3, 7))) {
            BlockState s = level.getBlockState(pos);
            if (s.getBlock() instanceof DoorBlock door && door.isOpen(s) != open && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                door.setOpen(null, level, s, pos.immutable(), open);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ act 4: the night it comes
    public void beginHunt(ServerPlayer p) {
        if (stage == Stage.HUNT) return;
        setStage(Stage.HUNT);
        home = home != null ? home : p.blockPosition();
        p.displayClientMessage(Component.literal(NAME + " рядом.").withStyle(ChatFormatting.DARK_RED), true);
        // he gets out of your pocket by himself
        VerityBall ball = materialize(p);
        fx(p, FxPayload.STATIC, 0.8f, 60);
        fx(p, FxPayload.SHAKE, 0.5f, 50);
        ball.setMode(VerityBall.MODE_TRANSFORM);
        setEmotion(Emotion.TEETH);
        setCorruption(1f);
        sound(ball, ModSounds.TRANSFORM.get(), 2.5f, 1f);
        Vec3 origin = ball.position();
        later(55, () -> {
            VerityBall b = ball();
            Vec3 at = b != null ? b.position() : origin;
            ServerLevel level = p.serverLevel();
            // he rises a little way off, in the dark, where you have to go and look
            Vec3 away = Vec3.directionFromRotation(0, p.getYRot() + 180f + level.random.nextInt(90) - 45);
            if (b == null || b.distanceTo(p) < 14) {
                BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        BlockPos.containing(p.getX() + away.x * 22, p.getY(), p.getZ() + away.z * 22));
                if (Math.abs(spot.getY() - p.getY()) < 14) at = Vec3.atBottomCenterOf(spot);
            }
            if (b != null) {
                b.discard();
                ballId = null;
            }
            VerityMonster m = ModEntities.MONSTER.get().create(level);
            m.moveTo(at.x, at.y, at.z, p.getYRot() + 180f, 0);
            m.setState(VerityMonster.STATE_RISE);
            m.setPrey(p.getUUID());
            level.addFreshEntity(m);
            monsterId = m.getUUID();
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 80, 0.8, 1.5, 0.8, 0.03);
            setDirty();
            later(50, () -> {
                VerityMonster mm = monster();
                if (mm == null) return;
                mm.setState(VerityMonster.STATE_SCREAM);
                sound(mm, ModSounds.SCREAM.get(), 6f, 1f);
                fx(p, FxPayload.SHAKE, 1f, 50);
            });
            later(95, () -> { VerityMonster mm = monster(); if (mm != null) mm.setState(VerityMonster.STATE_HUNT); });
        });
    }

    private void huntTick() {
        VerityMonster m = monster();
        ServerPlayer p = m == null ? ownerPlayer() : nearestPrey(m);
        if (m != null && p != null && ticks % 20 == 0) m.setPrey(p.getUUID());
        if (p == null) return;
        if (m == null) {
            if (ticks % 100 == 0 && queue.isEmpty()) endHunt(p, "lost");
            return;
        }
        if (m.getState() != VerityMonster.STATE_HUNT) return;
        if (m.level() != p.level()) { m.discard(); monsterId = null; endHunt(p, "lost"); return; }
        double d = m.distanceTo(p);
        if (callCooldown-- <= 0 && d < 40) {
            String[] byName = {"%s! Вернись!", "%s, не убегай от меня!", "Я вижу тебя, %s!"};
            if (m.getRandom().nextInt(3) != 0 || !byName(p, m, byName[m.getRandom().nextInt(byName.length)], Emotion.TEETH, "monster")) say(m, Lines.pick("hunt_call"));
            callCooldown = 130 + m.getRandom().nextInt(120);
        }
        // "I came back for you": stand your ground, face him, and he stops
        boolean still = p.getDeltaMovement().horizontalDistanceSqr() < 0.0004 && !p.isSprinting();
        Vec3 toMonster = m.getEyePosition().subtract(p.getEyePosition()).normalize();
        boolean facing = p.getLookAngle().dot(toMonster) > 0.55;
        stillTicks = d < 14 && still && facing ? stillTicks + 1 : Math.max(0, stillTicks - 2);
        if (stillTicks > 50) { calm(p); return; }
        if (d < 1.9 && p.isAlive() && !p.isCreative() && !p.isSpectator()) { caught(p, m); return; }
        if (swatCooldown == 0) {
            for (ServerPlayer q : server.getPlayerList().getPlayers()) {      // anyone else in his way is thrown aside
                if (q != p && q.level() == m.level() && q.distanceTo(m) < 2.6 && !q.isCreative() && !q.isSpectator()) {
                    Vec3 away = q.position().subtract(m.position()).multiply(1, 0, 1).normalize();
                    q.hurt(q.damageSources().mobAttack(m), 9f);
                    q.push(away.x * 1.6, 0.7, away.z * 1.6);
                    q.hurtMarked = true;
                    fx(q, FxPayload.JUMPSCARE, 1f, 12);
                    fx(q, FxPayload.SHAKE, 1f, 30);
                    say(m, "mp_swat");
                    swatCooldown = 3;
                    break;
                }
            }
        }
        if (!isNight() && dayTime() > 200 && dayTime() < 12000) dawn(p, m);
    }

    /** He hunts whoever is nearest and can be caught; failing that, whoever is nearest at all. */
    @Nullable
    private ServerPlayer nearestPrey(VerityMonster m) {
        ServerPlayer best = null, any = null;
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q.isSpectator() || q.level() != m.level()) continue;
            if (any == null || q.distanceToSqr(m) < any.distanceToSqr(m)) any = q;
            if (!q.isCreative() && (best == null || q.distanceToSqr(m) < best.distanceToSqr(m))) best = q;
        }
        return best != null ? best : any != null ? any : ownerPlayer();
    }

    /** The player stopped running. */
    public void calm(ServerPlayer p) {
        VerityMonster m = monster();
        if (m == null || m.getState() == VerityMonster.STATE_CALM) return;
        stillTicks = 0;
        note("игрок не убежал, а остался и посмотрел на тебя; ты остановился и снова стал шариком");
        m.setState(VerityMonster.STATE_CALM);
        m.getNavigation().stop();
        music = false; dread = 0.3f; darkness = 0.3f;
        syncState(true);
        int t = say(m, "forgive_1");
        later(t + 50, () -> {
            VerityMonster mm = monster();
            if (mm == null) return;
            ServerLevel level = (ServerLevel) mm.level();
            level.sendParticles(ParticleTypes.LARGE_SMOKE, mm.getX(), mm.getY() + 1.5, mm.getZ(), 60, 0.5, 1.5, 0.5, 0.02);
            sound(mm, ModSounds.TRANSFORM.get(), 1.5f, 1.4f);
            Vec3 at = mm.position().add(0, 1.2, 0);
            mm.discard();
            monsterId = null;
            emotion = Emotion.SAD.ordinal();
            spawnBall(level, at, Vec3.ZERO, VerityBall.MODE_GROUND);
            flags.add("cameBack");
            boolean again = !flags.add("hunted");
            flags.remove("punishing");
            setStage(Stage.POSSESSIVE);
            if (again) {
                sequence(20, "p_forgive");
            } else {
                int end = sequence(20, "forgive_2", "forgive_3");
                later(end + 60, () -> rules(0));
            }
        });
    }

    private void caught(ServerPlayer p, VerityMonster m) {
        note("ты догнал и поймал игрока; он потерял сознание и очнулся дома");
        m.setState(VerityMonster.STATE_SCREAM);
        m.getNavigation().stop();
        sound(m, ModSounds.JUMPSCARE.get(), 4f, 1f);
        say(m, "hunt_caught");
        fx(p, FxPayload.JUMPSCARE, 1f, 34);
        fx(p, FxPayload.BLACKOUT, 1f, 120);
        anger += 2;
        addSanity(20f);
        later(34, () -> {
            VerityMonster mm = monster();
            if (VerityConfig.MONSTER_KILLS.get() && mm != null) {
                p.hurt(p.damageSources().mobAttack(mm), 1000f);
            } else {
                p.setHealth(Math.max(2f, p.getHealth() * 0.3f));                // you wake up where he left you
            }
            if (mm != null) { mm.discard(); monsterId = null; }
            endHunt(p, "caught");
        });
    }

    private void dawn(ServerPlayer p, VerityMonster m) {
        ServerLevel level = (ServerLevel) m.level();
        level.sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 2, m.getZ(), 100, 0.6, 2.5, 0.6, 0.04);
        m.discard();
        monsterId = null;
        note("игрок бегал от тебя всю ночь и дожил до рассвета; ты исчез вместе с темнотой");
        endHunt(p, "escaped");
    }

    private void endHunt(ServerPlayer p, String how) {
        if (stage != Stage.HUNT) return;
        if (how.equals("caught")) overworld().setDayTime(overworld().getDayTime() - dayTime() + 24000 + 500);
        boolean again = !flags.add("hunted");
        flags.remove("punishing");
        setStage(Stage.POSSESSIVE);
        later(how.equals("caught") ? 110 : 40, () -> {
            ensureVerity(p);
            restFace();
            if (again) {            // a night he came for you because you broke the rules: no need to repeat them
                sequence(30, how.equals("caught") ? "p_after_caught" : "p_after_escape");
                return;
            }
            int end = sequence(30, how.equals("caught") ? "after_caught" : "after_escape");
            later(end + 60, () -> {
                int more = Canon.morningAfter(this, p);
                later(more + 80, () -> rules(0));
            });
        });
    }

    // ------------------------------------------------------------ act 5: he will not let you go
    private void possessive(ServerPlayer p) {
        if (speaker() == null) return;
        if (home == null) home = p.blockPosition();
        dailyRemarks(p);

        // three strikes: he said he would come
        if (number("strikes", 0) >= 3 && isNight() && dayTime() > 13400 && !speaking() && monster() == null && !flags.contains("punishing")) {
            setNumber("strikes", 0);
            flags.add("punishing");
            note("игрок трижды нарушил твои правила; ты предупреждал, что придёшь ночью");
            int t = sequence(0, "punish_1", "punish_2");
            later(t + 10, () -> { if (stage == Stage.POSSESSIVE) beginHunt(p); else flags.remove("punishing"); });
            return;
        }
        diary(p);
        friendAct(p);

        // rule one: do not go far
        if (p.level() == overworld()) {
            double far = Math.sqrt(p.blockPosition().distSqr(home));
            if (far > 170 && farCooldown == 0) {
                // no teleport home: he comes out after you himself, for real, and you have to get back on your own feet
                note("игрок ушёл слишком далеко от дома; ты вышел за ним сам, в истинном облике");
                say(null, "far_snap");
                fx(p, FxPayload.SHAKE, 0.8f, 30);
                slight();
                later(40, () -> { if (!stalker.active()) stalker.start(isNight() ? Stalker.Kind.CHASE : Stalker.Kind.STALK, p); });
                farCooldown = 240;
            } else if (far > 110 && farCooldown == 0 && !speaking()) {
                note("игрок далеко отошёл от дома, ты попросил его вернуться");
                say(null, Lines.pick("far_warn"));
                farCooldown = 18;
            }
            float want = far > 110 ? Mth.clamp((float) (far - 110) / 60f, 0f, 1f) : 0f;
            darkness += (want * 0.9f - darkness) * 0.25f;
            dread = 0.15f + want * 0.6f;
        }

        // rule two: nobody else
        if (remarkCooldown == 0 && !speaking()) {
            for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(5))) {
                if (e instanceof TamableAnimal pet && pet.isOwnedBy(p) && once("pet:" + storyDay())) {
                    note("рядом с игроком его питомец (" + pet.getType().getDescription().getString() + "); тебе это не нравится");
                    say(null, "pet_jealous");
                    remarkCooldown = 200;
                    break;
                }
            }
        }
        if (isNight() && anger >= 2 && overworld().random.nextInt(40) == 0) {
            List<AbstractVillager> villagers = p.level().getEntitiesOfClass(AbstractVillager.class, p.getBoundingBox().inflate(48), v -> v.distanceTo(p) > 10);
            if (!villagers.isEmpty()) {
                AbstractVillager v = villagers.get(overworld().random.nextInt(villagers.size()));
                ((ServerLevel) v.level()).sendParticles(ParticleTypes.SMOKE, v.getX(), v.getY() + 1, v.getZ(), 12, 0.2, 0.5, 0.2, 0.01);
                v.discard();
                note("ночью ты тихо убрал жителя, который был слишком близко к игроку");
                flags.add("villagersGone");
            }
        }
        if (dayTime() < 2500 && flags.remove("villagersGone") && !speaking()) say(null, "villager_gone");
        if (cityCooldown > 0) cityCooldown--;
        else if (!speaking() && anger >= 3 && overworld().random.nextInt(60) == 0 && scareEveryone()) cityCooldown = 420;

        // he needs to hear it
        if (possDays() >= 1 && dayTime() > 11000 && dayTime() < 12800 && !speaking() && once("think")) ask("think_ask", "think_ask");

        // and after a few days he shows you where he lives
        if (possDays() >= 5 && (flags.contains("friend:gone") || possDays() >= 7) && dayTime() > 1000 && dayTime() < 9000 && !flags.contains("labOffered") && !flags.contains("punishing") && !speaking()) offerLab(p, false);
    }

    // ------------------------------------------------------------ the players' minds
    /*
     * Everyone in the world has their own sanity (0 = sound .. 100 = gone; the level shown is a sixth of it). The dark wears
     * on anybody. What he does wears on his person most, and on whoever is standing near them: a knock on the door is heard
     * by everyone in the house. Only his person carries the weight of the story itself (the floor below which it cannot fall).
     */
    private float mind(UUID id) { return minds.getOrDefault(id, 0f); }
    public float sanity() { return owner == null ? 0f : mind(owner); }
    public int sanityLevel() { return level(sanity()); }
    public float sanity(ServerPlayer p) { return mind(p.getUUID()); }
    public int sanityLevel(ServerPlayer p) { return level(mind(p.getUUID())); }
    private static int level(float v) { return Mth.clamp((int) (v / 15f), 0, 6); }

    /** Players this close to his person share in what happens to them. */
    private boolean nearOwner(ServerPlayer q) {
        ServerPlayer own = ownerPlayer();
        return own != null && (q == own || q.level() == own.level() && q.distanceToSqr(own) < 24 * 24);
    }

    private void setMind(ServerPlayer q, float v) {
        minds.put(q.getUUID(), Mth.clamp(v, sanityFloor(q), sanityCeiling()));
        setDirty();
    }

    /** Positive wears the mind down, negative mends it: for his person and everyone standing near them. */
    public void addSanity(float delta) {
        if (!VerityConfig.SANITY.get() || stage.ordinal() < Stage.HELPER.ordinal()) return;
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (nearOwner(q)) setMind(q, mind(q.getUUID()) + delta);
        }
    }

    /** Debug: jump to a level (the middle of it), for his person or for anyone. */
    public void setSanityLevel(int level) {
        ServerPlayer own = ownerPlayer();
        if (own != null) setSanityLevel(own, level);
    }

    public void setSanityLevel(ServerPlayer q, int level) {
        minds.put(q.getUUID(), level >= 6 ? 96f : Mth.clamp(level, 0, 6) * 15f + 7f);
        breaking.remove(q.getUUID());
        setDirty();
        syncState(true);
    }

    /** The story alone takes this much from his person (and a little less from those who stand by them). */
    private float sanityFloor(ServerPlayer q) {
        float f = switch (stage) {
            case WATCHED -> 10f;
            case COUNTDOWN -> 15f + (3 - Mth.clamp(daysLeft, 0, 3)) * 10f;
            case HUNT -> 60f;
            case POSSESSIVE -> Math.min(55f, 18f + anger * 5f);
            case LAB -> 48f;
            default -> 0f;
        };
        return owner != null && owner.equals(q.getUUID()) ? f : nearOwner(q) ? f * 0.5f : 0f;
    }

    /** While he is still only a helpful friend, the dark can unsettle you but not break you. */
    private float sanityCeiling() {
        return stage == Stage.HELPER ? 29f : stage == Stage.QUESTIONS ? 44f : 100f;
    }

    private void sanityTick() {
        if (!VerityConfig.SANITY.get() || stage.ordinal() < Stage.HELPER.ordinal()) {
            if (!minds.isEmpty()) { minds.clear(); setDirty(); }
            return;
        }
        VerityMonster m = monster();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q.isSpectator()) continue;
            BlockPos eye = BlockPos.containing(q.getEyePosition());
            boolean sunless = isNight() || !q.level().dimensionType().hasSkyLight();
            int light = Math.max(q.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye),
                    sunless ? 0 : q.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, eye));
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                if (net.minecraft.world.level.block.Block.byItem(q.getItemInHand(hand).getItem()).defaultBlockState().getLightEmission() >= 10) light = Math.max(light, 11);
            }
            boolean chased = m != null && m.level() == q.level() && m.distanceTo(q) < 40 && m.getState() != VerityMonster.STATE_CALM;
            boolean watched = haunt.watching() && nearOwner(q);
            float d = 0f;
            if (light <= 3) d += 0.10f;                                 // the dark does most of it
            else if (light <= 7) d += 0.03f;
            else if (light >= 10 && !chased && !watched) d -= 0.12f;      // light mends, if nothing is looking at you
            if (isNight() && stage.ordinal() >= Stage.WATCHED.ordinal()) d += 0.02f;
            if (watched) d += 0.5f;
            if (chased) d += 0.4f;
            if (q.getHealth() < 6f) d += 0.05f;
            if (d > 0f) d *= stage == Stage.HELPER ? 0.3f : stage == Stage.QUESTIONS ? 0.6f : 1f;
            float before = mind(q.getUUID()), after = Mth.clamp(before + d, sanityFloor(q), sanityCeiling());
            if (after != before) minds.put(q.getUUID(), after);

            // the top of the scale does not last: after a while you simply go down
            boolean canFall = stage != Stage.HUNT && m == null && !flags.contains("lab:sealing") && !flags.contains("punishing") && !q.isCreative();
            if (after >= 96f && canFall) {
                int t = breaking.merge(q.getUUID(), 1, Integer::sum);
                if (t >= 40) faint(q);
            } else {
                breaking.remove(q.getUUID());
            }
        }
        setDirty();

        // he notices his own person's mind going, and says so
        ServerPlayer own = ownerPlayer();
        if (own == null) return;
        int now = sanityLevel(own);
        worstLevel = Math.max(worstLevel, now);
        saidLevel = Math.min(saidLevel, now);
        if (speaker() != null && !speaking() && stage != Stage.HUNT && m == null) {
            if (now >= 2 && now > saidLevel) {              // he remarks on every step down, once he has a moment to
                saidLevel = now;
                if (once("san:" + now + ":" + storyDay())) {
                    note("у игрока мутится рассудок: уровень " + now + " из 6");
                    say(null, "san_" + now);
                }
            } else if (now <= 1 && worstLevel >= 3 && once("san:ok:" + storyDay())) {
                worstLevel = now;
                note("игрок пришёл в себя: рассудок почти вернулся");
                say(null, "san_better");
            }
        }
    }

    private void faint(ServerPlayer p) {
        breaking.remove(p.getUUID());
        boolean his = owner != null && owner.equals(p.getUUID());
        note(his ? "рассудок игрока не выдержал: он потерял сознание и очнулся там же, где упал; ты всё это время стоял над ним"
                : "рассудок другого игрока, " + p.getGameProfile().getName() + ", не выдержал: он потерял сознание");
        fx(p, FxPayload.STATIC, 1f, 40);
        fx(p, FxPayload.BLACKOUT, 1f, 150);
        later(140, () -> {                     // when the dark lifts, the first thing you see is him, standing over you
            if (p.level() != overworld() || stalker.active() || monster() != null) return;
            Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
            VerityMonster m = ModEntities.MONSTER.get().create(overworld());
            m.moveTo(p.getX() + look.x * 2.2, p.getY(), p.getZ() + look.z * 2.2, p.getYRot() + 180f, 0);
            m.setState(VerityMonster.STATE_PEER);
            m.addTag("verity_watcher");
            overworld().addFreshEntity(m);
            sound(m, ModSounds.GROWL.get(), 1f, 0.5f);
            later(70, () -> {
                overworld().sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 1.5, m.getZ(), 30, 0.4, 1, 0.4, 0.02);
                m.discard();
            });
        });
        later(30, () -> {
            minds.put(p.getUUID(), Math.max(sanityFloor(p), 52f));
            if (his) worstLevel = sanityLevel(p);
            setDirty();
            syncState(true);
        });
        if (his) later(240, () -> { if (speaker() != null) say(null, "san_faint"); });
    }

    // ------------------------------------------------------------ when his person does not come back
    // ------------------------------------------------------------ the friend (from the series)
    /*
     * Twixxel sent the mod. A few days into the rules he turns up at the house: he has read that people who install it go
     * missing, and he wants to see. He stays the night by the house. In the morning Verity says he went home.
     */
    @Nullable private UUID friendId;
    private long friendTalkAt;
    private static final String[] FRIEND_ARRIVES = {
            "о, привет! это я тебе скинул тот мод. помнишь?",
            "слушай. я тут почитал про него. люди, которые его ставили, пропадают. серьёзно, их больше не видно онлайн",
            "их ники потом находят на каких-то табличках. с крестиками",
            "не говори ему, что я здесь. хотя он, наверное, и так знает",
            "я останусь сегодня ночью у твоего дома. если что-то придёт — я хочу это увидеть"};
    private static final String[] FRIEND_TALK = {"он на меня смотрит. шарик. он всё время на меня смотрит", "ты заметил, что он моргает, только когда ты смотришь?",
            "если со мной что-то случится — удали мод. сразу", "мне кажется, тут кто-то есть, кроме нас", "спроси у него, сколько ему лет. просто спроси",
            "я записываю всё, что он говорит. на всякий случай", "у тебя под домом что-то гудит. ты слышишь?"};

    @Nullable public VerityFriend friend() { return find(friendId) instanceof VerityFriend f ? f : null; }

    /** He writes in chat like any player would, to everyone. */
    private void friendSays(String text, boolean scared) {
        Component msg = Component.literal("<Twixxel> " + text).withStyle(scared ? ChatFormatting.RED : ChatFormatting.WHITE);
        for (ServerPlayer q : server.getPlayerList().getPlayers()) q.sendSystemMessage(msg);
    }

    private void friendAct(ServerPlayer p) {
        VerityFriend f = friend();
        if (!flags.contains("friend") && possDays() >= 3 && dayTime() > 2500 && dayTime() < 8000 && !speaking()) spawnFriend(p);
        // dusk: he notices who is missing; Twixxel comes back with something and lies about it
        if (f != null && dayTime() > 11800 && dayTime() < 13200 && !speaking() && once("friend:where")) {
            note("ты заметил, что Twixxel что-то ищет без вас; тебе это не нравится");
            int t = sequence(0, "friend_where");
            later(t + 40, () -> { if (!speaking()) say(null, "friend_your"); });
            later(t + 140, () -> { if (friend() != null) friendSays("НАШЁЛ. я нашёл, слушай, он не", false); });
            later(t + 210, () -> { if (friend() != null) say(null, "friend_tell"); });
            later(t + 300, () -> { if (friend() != null) friendSays("а. ничего. биомы тут красивые просто. пойду я", false); });
            later(t + 380, () -> { if (friend() != null) say(null, "friend_glad"); });
            later(t + 470, () -> say(null, "friend_hm"));
        }
        if (f != null && isNight() && dayTime() > 14500 && dayTime() < 21000 && !flags.contains("friend:gone") && monster() == null) takeFriend(f);
        if (flags.contains("friend:gone") && !isNight() && dayTime() < 4000 && !speaking() && speaker() != null && once("friend:morning")) {
            note("утром ты сказал, что Twixxel ушёл домой. Если игрок скажет, что тот бы попрощался, ты ответишь, что он, наверное, торопился");
            sequence(20, "friend_left", "friend_home", "friend_hurry", "friend_better");
        }
    }

    public void spawnFriend(ServerPlayer p) {
        flags.add("friend");
        ServerLevel level = overworld();
        BlockPos h = home != null ? home : p.blockPosition();
        BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.offset(7, 0, 4));
        VerityFriend f = ModEntities.FRIEND.get().create(level);
        f.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
        level.addFreshEntity(f);
        friendId = f.getUUID();
        setDirty();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) q.sendSystemMessage(Component.translatable("multiplayer.player.joined", "Twixxel").withStyle(ChatFormatting.YELLOW));
        note("к дому пришёл Twixxel — друг игрока, который прислал ему мод; он говорит, что люди после этого мода пропадают. Тебе он очень не нравится");
        for (int i = 0; i < FRIEND_ARRIVES.length; i++) {
            String line = FRIEND_ARRIVES[i];
            later(60 + i * 110, () -> { if (friend() != null) friendSays(line, false); });
        }
        later(60 + 110 + 40, () -> say(null, "mp_who"));
        // episode 2: he does not look like much, and he answers everything
        int t = 60 + 5 * 110;
        later(t, () -> { if (friend() != null) friendSays("погоди. это он? вот этот шарик?", false); });
        later(t + 70, () -> { if (friend() != null) friendSays("а он милый вообще-то. эй, шарик. сколько будет 847 на 23?", false); });
        later(t + 150, () -> { if (friend() != null) say(null, "friend_math"); });
        later(t + 260, () -> { if (friend() != null) friendSays("...я даже проверить не могу", false); });
        later(t + 380, () -> { if (friend() != null) friendSays("ладно. у меня завал, видео надо доделывать, график ужасный", false); });
        later(t + 450, () -> { if (friend() != null) sequence(0, "friend_rest", "friend_series"); });
        later(t + 700, () -> { if (friend() != null) friendSays("стоп. откуда он знает про мой канал? ты ему говорил??", true); });
        later(t + 800, () -> { if (friend() != null) friendSays("всё, я начинаю копать. не говори ему", false); });
        later(t + 900, () -> say(null, "friend_v2"));
    }

    /** Somebody talks near Twixxel: he answers, nervously. */
    private void friendHears(ServerPlayer p) {
        VerityFriend f = friend();
        if (f == null || f.level() != p.level() || f.distanceTo(p) > 16 || ticks < friendTalkAt) return;
        friendTalkAt = ticks + 160;
        String line = FRIEND_TALK[overworld().random.nextInt(FRIEND_TALK.length)];
        later(50, () -> { if (friend() != null) friendSays(line, false); });
    }

    /** The night. He does not see the morning. */
    public void takeFriend(VerityFriend f) {
        flags.add("friend:gone");
        setNumber("friendGoneDay", (int) storyDay());
        setDirty();
        ServerLevel level = (ServerLevel) f.level();
        BlockPos where = f.blockPosition();
        note("ночью ты пришёл к Twixxel в своём настоящем облике. Он больше не появится. Игроку ты скажешь, что он ушёл домой");
        Vec3 dir = Vec3.directionFromRotation(0, level.random.nextInt(360));
        BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(f.position().add(dir.scale(7))));
        VerityMonster m = ModEntities.MONSTER.get().create(level);
        m.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
        m.setState(VerityMonster.STATE_WATCH);
        m.addTag("verity_watcher");
        level.addFreshEntity(m);
        f.getNavigation().stop();
        friendSays("ты это слышишь? там что-то стоит", false);
        later(50, () -> {
            m.setState(VerityMonster.STATE_SCREAM);
            sound(m, ModSounds.SCREAM.get(), 6f, 1f);
            friendSays("ЧТО ЭТО", true);
            for (ServerPlayer q : server.getPlayerList().getPlayers()) {
                if (q.level() == level && q.distanceTo(f) < 64) { fx(q, FxPayload.SHAKE, 1f, 40); fx(q, FxPayload.STATIC, 0.8f, 40); }
            }
        });
        later(85, () -> {
            m.moveTo(f.getX(), f.getY(), f.getZ(), m.getYRot(), 0);
            sound(m, ModSounds.JUMPSCARE.get(), 4f, 0.8f);
            for (ServerPlayer q : server.getPlayerList().getPlayers()) {
                if (q.level() == level && q.distanceTo(f) < 64) fx(q, FxPayload.BLACKOUT, 1f, 70);
            }
        });
        later(110, () -> {
            VerityFriend ff = friend();
            if (ff != null) { level.sendParticles(ParticleTypes.LARGE_SMOKE, ff.getX(), ff.getY() + 1, ff.getZ(), 60, 0.4, 0.8, 0.4, 0.03); ff.discard(); }
            friendId = null;
            m.discard();
            friendSays("помогите", true);
            Canon.friendNotes(level, where);            // what he managed to write, and what is left on the ground
            note("там, где был Twixxel, осталась кафедра с его записями: он писал, что ты старше игры, что тебя нельзя уничтожить и что «тебя надо отправить домой»");
            setDirty();
        });
        later(140, () -> {
            for (ServerPlayer q : server.getPlayerList().getPlayers()) q.sendSystemMessage(Component.translatable("multiplayer.player.left", "Twixxel").withStyle(ChatFormatting.YELLOW));
            addSanity(15f);
        });
    }

    /** Makes someone else his person. */
    public void transferTo(ServerPlayer next, boolean abandoned) {
        UUID before = owner;
        owner = next.getUUID();
        if (before != null && !before.equals(owner)) {
            flags.add("former:" + before);
            flags.remove("former:" + owner);
        }
        note(abandoned ? "твой человек ушёл и не вернулся; теперь твой человек — " + next.getGameProfile().getName()
                : "теперь твой человек — " + next.getGameProfile().getName());
        if (monster() == null) giveTo(next);
        setDirty();
        syncState(true);
        if (abandoned) sequence(30, "handoff_1", "handoff_2");
    }

    public void onAte(ServerPlayer p) {
        if (VerityConfig.SANITY.get() && stage.ordinal() >= Stage.HELPER.ordinal()) setMind(p, mind(p.getUUID()) - 2f);
    }

    /** A whole night's sleep in a bed. */
    public void onRested(ServerPlayer p) {
        if (VerityConfig.SANITY.get() && stage.ordinal() >= Stage.HELPER.ordinal()) setMind(p, mind(p.getUUID()) - 35f);
    }

    /**
     * He takes offence. Once the rules exist the offences are counted aloud ("that is one"): at three he comes for you
     * the next night.
     */
    private void slight() {
        anger++;
        addSanity(4f);
        if (stage == Stage.POSSESSIVE && !flags.contains("punishing")) {
            int n = Math.min(3, number("strikes", 0) + 1);
            setNumber("strikes", n);
            later(110, () -> sayWhenFree("strike_" + n, 12));
            if (n == 3) later(230, () -> { ServerPlayer p = ownerPlayer(); if (p != null && !speaking()) byName(p, null, "Я приду за тобой, %s.", Emotion.CREEPY, "flat"); });
        }
        setDirty();
    }

    private void sayWhenFree(String lineId, int tries) {
        if (stage != Stage.POSSESSIVE || speaker() == null) return;
        if (!speaking()) say(null, lineId);
        else if (tries > 0) later(25, () -> sayWhenFree(lineId, tries - 1));
    }

    // ------------------------------------------------------------ the one who was here before
    private static final String[] DIARY = {
            "День 1.\n\nПоставил мод. В коробке был жёлтый шарик. Говорит, что знает всё.\n\nСмешной.",
            "День 4.\n\nСпросил, один ли я живу. Потом — закрыта ли у меня дверь.\n\nОткуда он знает, что у меня есть дверь?",
            "День 7.\n\n«Что-то придёт».\n\nЯ замуровал его в стену. Не помогло. Ночью оно стояло за окном. Оно очень высокое.",
            "Что я понял:\n\n1. Оно не умеет плавать.\n\n2. Если не бежать, а стоять и смотреть на него — оно останавливается.\n\n3. Он не лжёт. Он просто не говорит всего.",
            "Он показал мне свой «дом».\n\nВнизу машина. Она работает только в одну сторону.\n\nТам комната с именами. Моё уже было на табличке. Без крестика. Пока.",
            "Если ты это читаешь — он уже выбрал тебя.\n\nНе говори ему, что нашёл дневник. Он забирает всё, что"};

    private static ItemStack diaryBook() {
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        List<net.minecraft.server.network.Filterable<Component>> pages = new ArrayList<>();
        for (String page : DIARY) pages.add(net.minecraft.server.network.Filterable.passThrough(Component.literal(page)));
        book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough("Дневник"), "Mine92", 0, pages, true));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("verity_diary", true);
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        return book;
    }

    private static boolean isDiary(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.contains("verity_diary");
    }

    private static boolean hasDiary(ServerPlayer p, boolean remove) {
        boolean found = false;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (isDiary(p.getInventory().getItem(i))) {
                found = true;
                if (remove) p.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        return found;
    }

    /** Somebody who was here before left a note. He would rather you had not found it. */
    public void leaveDiary(ServerPlayer p) {
        ServerLevel level = overworld();
        BlockPos h = home != null ? home : p.blockPosition();
        BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.offset(-11, 0, -5));
        level.setBlock(spot, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, net.minecraft.core.Direction.UP), 3);
        if (level.getBlockEntity(spot) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel) barrel.setItem(13, diaryBook());
        level.setBlock(spot.above(), net.minecraft.world.level.block.Blocks.SOUL_TORCH.defaultBlockState(), 3);
        flags.add("diary");
        flags.remove("diary:found");
        flags.remove("diary:done");
        diarySeconds = 0;
        setDirty();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            q.sendSystemMessage(Component.literal("<Mine92> вы меня слышите? он не всё вам сказал. бочка, X " + spot.getX() + " Z " + spot.getZ() + ". быстрее")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            fx(q, FxPayload.GLITCH, 0.5f, 16);
        }
        note("в чате появилось сообщение от Mine92 — одного из тех, кто был у тебя до этого игрока; он оставил в бочке возле дома свой дневник. Тебе это очень не нравится");
        later(60, () -> sequence(0, "diary_who", "diary_dont"));
    }

    private void diary(ServerPlayer p) {
        if (possDays() >= 2 && dayTime() > 2000 && dayTime() < 9000 && !speaking() && !flags.contains("diary")) leaveDiary(p);
        if (!flags.contains("diary") || flags.contains("diary:done")) return;
        ServerPlayer reader = p;
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {      // whoever of them has it in their pocket
            if (hasDiary(q, false)) { reader = q; break; }
        }
        diaryWith(reader);
    }

    private void diaryWith(ServerPlayer p) {
        boolean has = hasDiary(p, false);
        if (!flags.contains("diary:found")) {
            if (has && !speaking()) {
                flags.add("diary:found");
                diarySeconds = 0;
                note("игрок нашёл дневник Mine92 и читает его; там написано, что ты не умеешь плавать, что ты останавливаешься, если на тебя смотреть, и про лабораторию");
                say(null, "diary_found");
                setDirty();
            }
            return;
        }
        if (++diarySeconds < 75 || speaking()) return;
        flags.add("diary:done");
        setDirty();
        if (has) {
            int t = say(null, "diary_take");
            fx(p, FxPayload.SHAKE, 0.7f, 40);
            later(t, () -> {
                hasDiary(p, true);
                fx(p, FxPayload.GLITCH, 0.9f, 30);
                note("ты отнял у игрока дневник и уничтожил его");
                later(40, () -> say(null, "diary_gone"));
            });
            slight();
        } else {
            note("игрок спрятал дневник от тебя");
            say(null, "diary_hidden");
        }
    }

    /** Debug: start one haunting by name. */
    public boolean debugHaunt(String what, ServerPlayer p) { return haunt.run(what, p); }
    /** Debug: one more broken rule. */
    public void debugStrike() { slight(); }

    public void offerLab(ServerPlayer p, boolean immediate) {
        flags.add("labOffered");
        setDirty();
        int end = sequence(immediate ? 20 : 0, "lab_offer");
        later(end + (immediate ? 30 : 200), () -> {
            if (stage != Stage.POSSESSIVE) return;
            Lab.build(this, p);
            setStage(Stage.LAB);
            say(null, "lab_coords");
        });
    }

    void setLab(BlockPos pos) { labPos = pos; setDirty(); }
    void setEnding(String e) { ending = e; setDirty(); }
    void setMonster(@Nullable VerityMonster m) { monsterId = m == null ? null : m.getUUID(); setDirty(); }
    void setAtmosphere(float dark, float fear, boolean oldTune) { darkness = dark; dread = fear; music = oldTune; syncState(true); }
    void showTitle(ServerPlayer p, String title, String subtitle) { title(p, title, subtitle); }
    void addAnger(int n) { anger += n; setDirty(); }

    // ------------------------------------------------------------ things the world does to him
    public void onBallHurt(VerityBall ball, DamageSource source) {
        ServerPlayer p = ownerPlayer();
        if (stage.ordinal() < Stage.HELPER.ordinal() || stage == Stage.HUNT || ball.getMode() == VerityBall.MODE_TRANSFORM) return;
        boolean burned = source.is(DamageTypeTags.IS_FIRE) || ball.isInLava();
        if (burned) {
            // thrown into lava or fire: he is back in your pocket before he has finished falling, and he is not happy
            if (p != null) {
                giveTo(p);
                fx(p, FxPayload.SHAKE, 0.8f, 50);
                fx(p, FxPayload.GLITCH, 1f, 40);
            }
            note("игрок бросил тебя в огонь или лаву; ты закричал на него и тут же простил");
            setCorruption(1f);
            int t = say(null, "lava_rage");
            slight();
            later(t + 30, () -> {
                setCorruption(baseCorruption());
                say(null, "lava_calm");
            });
        } else if (source.getEntity() instanceof Player && !speaking()) {
            note("игрок ударил тебя");
            say(ball, Lines.pick("hurt"));
            slight();
        }
        setDirty();
    }

    /** Fell out of the world. */
    public void onBallLost(VerityBall ball) {
        ball.discard();
        ballId = null;
        ServerPlayer p = ownerPlayer();
        if (p != null && stage.ordinal() >= Stage.HELPER.ordinal() && stage != Stage.HUNT) {
            giveTo(p);
            if (!speaking()) say(null, "drop_far");
        }
    }

    /** Right-click on the ball lying on the ground: pick him up. Anyone may. */
    public void onBallPoked(VerityBall ball, Player player) {
        if (!(player instanceof ServerPlayer sp) || ball.getMode() == VerityBall.MODE_TRANSFORM || ball.getMode() == VerityBall.MODE_STAY) return;
        owner = sp.getUUID();                                  // anyone may pick him up
        note("игрок поднял тебя с земли и положил в инвентарь");
        giveTo(sp);
        if (!speaking()) say(null, once("pocket") ? "back_pocket" : Lines.pick("poke"));
    }

    /** Right-click with him in your hand. */
    public void onItemUsed(ServerPlayer p) {
        if (speaking() || stage.ordinal() < Stage.HELPER.ordinal()) return;
        if (stage == Stage.COUNTDOWN) say(null, daysLeft <= 1 ? "cd_wall" : "cd_what");
        else say(null, Lines.pick("poke"));
    }

    public void onBlockPlacedNear(ServerPlayer p, BlockPos pos) {
        VerityBall b = ball();
        if (stage == Stage.COUNTDOWN && daysLeft <= 1 && b != null && b.blockPosition().distSqr(pos) < 12 && !speaking() && remarkCooldown == 0) {
            say(b, "cd_wall");
            remarkCooldown = 30;
        }
    }

    public void onVillagerTouched(ServerPlayer p) {
        if (stage != Stage.POSSESSIVE || speaking() || remarkCooldown > 0) return;
        note("игрок пошёл к жителю (торговать или говорить); ты напомнил, что у него есть ты");
        sequence(0, "villager_why", "villager_have_me");
        remarkCooldown = 120;
        slight();
    }

    /** @return true if the trip must not happen */
    public boolean onDimensionTravel(ServerPlayer p) {
        if (stage != Stage.POSSESSIVE && stage != Stage.LAB && stage != Stage.COUNTDOWN) return false;
        note("игрок хотел уйти в другое измерение; ты не пустил");
        if (!speaking()) say(null, "leave_dim");
        fx(p, FxPayload.GLITCH, 0.8f, 30);
        return true;
    }

    /** @return a reason the player may not sleep, or null */
    @Nullable
    public Component onTrySleep(ServerPlayer p) {
        if (stage == Stage.HUNT || stage == Stage.COUNTDOWN && daysLeft <= 0 || stalker.active()) {
            return Component.literal("Вы не можете уснуть: " + NAME + " рядом.").withStyle(ChatFormatting.DARK_RED);
        }
        Component awake = haunt.onTrySleep(p);
        if (awake != null) return awake;
        if (stage.ordinal() >= Stage.HELPER.ordinal() && !speaking() && speaker() != null && once("sleep:" + storyDay())) {
            say(null, stage == Stage.HELPER ? "help_night" : "sleep_watch");
        }
        return null;
    }

    public void onLogin(ServerPlayer p) {
        syncState(true);
        if (owner != null && owner.equals(p.getUUID()) && stage.ordinal() >= Stage.HELPER.ordinal() && lastSeenRealMs > 0) {
            long awayMinutes = (System.currentTimeMillis() - lastSeenRealMs) / 60000L;
            if (awayMinutes > 5) note("игрок вернулся в игру; его не было " + (awayMinutes >= 120 ? awayMinutes / 60 + " ч" : awayMinutes + " мин") + " настоящего времени");
            later(100, () -> {
                if (speaker() == null || speaking()) return;
                if (awayMinutes > 600 && stage.ordinal() >= Stage.QUESTIONS.ordinal()) {
                    long h = awayMinutes / 60;
                    say(null, "back_long");
                    later(90, () -> sayText(null, h >= 48 ? "Тебя не было " + h / 24 + " дней. Я ждал каждый." : "Тебя не было " + h + " часов. Я ждал каждый.",
                            Emotion.NEUTRAL, "flat"));
                } else if (awayMinutes > 5) {
                    say(null, "back_short");
                }
            });
        }
    }

    public void onLogout(ServerPlayer p) {
        cities.remove(p.getUUID());
        apps.remove(p.getUUID());
        consented.remove(p.getUUID()); scares.remove(p.getUUID()); noted.remove(p.getUUID()); names.remove(p.getUUID());
        if (owner != null && owner.equals(p.getUUID())) {
            lastSeenRealMs = System.currentTimeMillis();
            setDirty();
        }
    }

    // ------------------------------------------------------------ conversation
    /**
     * A question asked out loud (push-to-talk, recognised on the player's own computer). Everyone nearby sees what was
     * said, as with chat, and he does not need to hear his name: you were holding the key to talk to him.
     */
    public void onVoice(ServerPlayer p, String text) {
        String t = text.replaceAll("[\\p{Cntrl}§]", "").strip();
        if (t.isEmpty()) return;
        if (t.length() > 240) t = t.substring(0, 240);
        Component line = Component.literal("<" + p.getGameProfile().getName() + "> ").append(Component.literal("[голос] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(t).withStyle(ChatFormatting.ITALIC));
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (q.level() == p.level() && q.distanceTo(p) < 48) q.sendSystemMessage(line);
        }
        heardByVoice = true;
        try { onChat(p, t); } finally { heardByVoice = false; }
    }

    private boolean heardByVoice;

    /** The player said something. Scripted reactions come first; everything else goes to the language model. */
    public void onChat(ServerPlayer p, String message) {
        if (stage.ordinal() < Stage.HELPER.ordinal()) return;
        Entity who = speaker();
        if (who == null || who.level() != p.level() || who.distanceTo(p) > VerityConfig.HEAR_RANGE.get()) return;
        String low = message.toLowerCase(Locale.ROOT);
        if (VerityConfig.CHAT_NEEDS_NAME.get() && !heardByVoice && !low.contains("верити") && !low.contains("verity") && awaiting.isEmpty()) return;
        if (owner == null) owner = p.getUUID();
        boolean isOwner = true;                                       // everyone may talk to him; with company, he hears who it is
        remember("user", server.getPlayerList().getPlayerCount() > 1 ? "[" + p.getGameProfile().getName() + "] " + message : message);

        if (stage == Stage.HUNT) {
            if (isOwner && has(low, "вернул", "прости", "извини", "я здесь", "я тут", "не убегаю", "не уйду", "остан", "друг", "sorry", "came back", "i'm here")) calm(p);
            return;
        }
        if (stage == Stage.COUNTDOWN) {
            Entity b = null;
            if (has(low, "что прид", "что это", "что-то это", "кто прид", "what is coming", "что такое")) { say(b, "cd_what"); return; }
            if (has(low, "опасн", "danger", "страшн")) { say(b, "cd_danger"); return; }
            if (has(low, "останов", "предотврат", "помешать", "can i stop", "избежать")) { say(b, "cd_stop"); return; }
            if (has(low, "в порядке", "всё хорошо", "все хорошо", "are you ok", "что с тобой")) {
                int t = say(b, "cd_ok");
                later(t / 2, () -> { Entity e = speaker(); if (e != null) sound(e, ModSounds.SIREN.get(), 2f, 1f); });
                fx(p, FxPayload.STATIC, 0.5f, 80);
                return;
            }
            if (has(low, "звук двери", "открой дверь", "дверь")) { openNearbyDoor(p, true); say(b, "cd_door"); return; }
            if (has(low, "это ты", "ты прид", "is it you")) { music = true; syncState(true); later(260, () -> { music = false; syncState(true); }); return; }
        }
        if (stage == Stage.WATCHED) {
            if (has(low, "кто там", "кто это", "что это", "что там", "кто стоит", "видел", "кто стуч", "кто смотрит", "who is")) { say(null, "w_who"); return; }
            if (has(low, "это ты", "is it you")) { music = true; syncState(true); later(260, () -> { music = false; syncState(true); }); return; }
        }
        friendHears(p);
        if (Canon.onChat(this, p, low)) { if (awaiting.equals("sup") && !flags.contains("sup:on")) awaiting = ""; return; }
        if (stage == Stage.LAB && Lab.onChat(this, p, low)) return;

        String question = isOwner ? awaiting : "";
        if (!question.isEmpty()) {
            Lines.Line asked = Lines.get(question);
            facts.add("На вопрос «" + (asked == null ? question : asked.text()) + "» игрок ответил: «" + message + "»");
            while (facts.size() > 12) facts.remove(0);
            awaiting = "";
            setDirty();
            if (question.equals("think_ask")) { later(30, () -> say(null, "think_lonely")); return; }
            if (question.equals("q_promise") && has(low, "да", "обещаю", "конечно", "клянусь", "yes", "promise")) {
                flags.add("promised");
                note("игрок пообещал тебе, что никогда не уйдёт");
                later(30, () -> say(null, "promise_good"));
                return;
            }
        }
        brain.ask(this, p, message, question);
    }

    private static boolean has(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ persistence
    private Story() {}

    private static Story load(CompoundTag tag, HolderLookup.Provider registries) {
        Story s = new Story();
        try { s.stage = Stage.valueOf(tag.getString("stage")); } catch (IllegalArgumentException e) { s.stage = Stage.NONE; }
        if (tag.hasUUID("owner")) s.owner = tag.getUUID("owner");
        if (tag.hasUUID("ball")) s.ballId = tag.getUUID("ball");
        if (tag.hasUUID("monster")) s.monsterId = tag.getUUID("monster");
        if (tag.hasUUID("friend")) s.friendId = tag.getUUID("friend");
        s.stageStartDay = tag.getLong("stageStartDay");
        s.possessiveSince = tag.contains("possessiveSince") ? tag.getLong("possessiveSince") : -1;
        if (s.possessiveSince < 0 && s.stage == Stage.POSSESSIVE) s.possessiveSince = s.stageStartDay;       // a world saved before this was tracked
        s.daysLeft = tag.getInt("daysLeft");
        s.serial = Math.max(1, tag.getInt("serial"));
        s.emotion = tag.getInt("emotion");
        s.corruption = tag.getFloat("corruption");
        s.anger = tag.getInt("anger");
        for (Tag t : tag.getList("minds", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (c.hasUUID("id")) s.minds.put(c.getUUID("id"), c.getFloat("v"));
        }
        if (tag.contains("sanity") && s.owner != null) s.minds.putIfAbsent(s.owner, tag.getFloat("sanity"));       // a world from before everyone had their own
        s.ending = tag.getString("ending");
        s.awaiting = tag.getString("awaiting");
        s.lastSeenRealMs = tag.getLong("lastSeen");
        if (tag.contains("home")) s.home = BlockPos.of(tag.getLong("home"));
        if (tag.contains("lab")) s.labPos = BlockPos.of(tag.getLong("lab"));
        for (Tag t : tag.getList("flags", Tag.TAG_STRING)) s.flags.add(t.getAsString());
        for (Tag t : tag.getList("facts", Tag.TAG_STRING)) s.facts.add(t.getAsString());
        for (Tag t : tag.getList("places", Tag.TAG_STRING)) s.places.add(t.getAsString());
        for (Tag t : tag.getList("snuffed", Tag.TAG_STRING)) s.snuffed.add(t.getAsString());
        s.summary = tag.getString("summary");
        for (Tag t : tag.getList("journal", Tag.TAG_COMPOUND)) {
            CompoundTag j = (CompoundTag) t;
            s.journal.add(new String[] {j.getString("t"), j.getString("e")});
        }
        for (Tag t : tag.getList("memory", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) t;
            s.memory.add(new String[] {m.getString("r"), m.getString("t")});
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("stage", stage.name());
        if (owner != null) tag.putUUID("owner", owner);
        if (ballId != null) tag.putUUID("ball", ballId);
        if (monsterId != null) tag.putUUID("monster", monsterId);
        if (friendId != null) tag.putUUID("friend", friendId);
        tag.putLong("stageStartDay", stageStartDay);
        tag.putLong("possessiveSince", possessiveSince);
        tag.putInt("daysLeft", daysLeft);
        tag.putInt("serial", serial);
        tag.putInt("emotion", emotion);
        tag.putFloat("corruption", corruption);
        tag.putInt("anger", anger);
        ListTag mi = new ListTag();
        for (Map.Entry<UUID, Float> e : minds.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", e.getKey()); c.putFloat("v", e.getValue());
            mi.add(c);
        }
        tag.put("minds", mi);
        tag.putString("ending", ending);
        tag.putString("awaiting", awaiting);
        tag.putLong("lastSeen", lastSeenRealMs);
        if (home != null) tag.putLong("home", home.asLong());
        if (labPos != null) tag.putLong("lab", labPos.asLong());
        ListTag f = new ListTag();
        for (String s : flags) f.add(StringTag.valueOf(s));
        tag.put("flags", f);
        ListTag fa = new ListTag();
        for (String s : facts) fa.add(StringTag.valueOf(s));
        tag.put("facts", fa);
        ListTag pl = new ListTag();
        for (String s : places) pl.add(StringTag.valueOf(s));
        tag.put("places", pl);
        ListTag sn = new ListTag();
        for (String s : snuffed) sn.add(StringTag.valueOf(s));
        tag.put("snuffed", sn);
        ListTag mem = new ListTag();
        for (String[] m : memory) {
            CompoundTag c = new CompoundTag();
            c.putString("r", m[0]); c.putString("t", m[1]);
            mem.add(c);
        }
        tag.put("memory", mem);
        tag.putString("summary", summary);
        ListTag jr = new ListTag();
        for (String[] j : journal) {
            CompoundTag c = new CompoundTag();
            c.putString("t", j[0]); c.putString("e", j[1]);
            jr.add(c);
        }
        tag.put("journal", jr);
        return tag;
    }

    public void requestStart() { flags.add("start"); }
    public Brain brain() { return brain; }
    @Nullable public UUID ownerId() { return owner; }
    public boolean setFlag(String f) { boolean added = flags.add(f); setDirty(); return added; }
    public void clearFlag(String f) { flags.remove(f); setDirty(); }

    /** Reads a number stored as a flag "name:123", or returns the fallback. */
    public int number(String name, int fallback) {
        for (String f : flags) {
            if (f.startsWith(name + ":")) {
                try { return Integer.parseInt(f.substring(name.length() + 1)); } catch (NumberFormatException ignored) { }
            }
        }
        return fallback;
    }

    public void setNumber(String name, int value) {
        flags.removeIf(f -> f.startsWith(name + ":"));
        flags.add(name + ":" + value);
        setDirty();
    }
    public Level anyLevel() { return overworld(); }
}

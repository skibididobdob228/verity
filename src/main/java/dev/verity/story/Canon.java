package dev.verity.story;

import dev.verity.ai.Locator;
import dev.verity.entity.Emotion;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModSounds;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Scenes taken from the three episodes that the acts themselves do not cover: the sounds he makes when asked, the face
 * that is not smiling, the emptied village in the east, the morning after the chase, Twixxel's notes, the "I didn't
 * know it was you", the borrowed voice, the recordings of the ones before, and the support line that cannot cancel him.
 * Retold in this mod's own words; the series' ending (the gun) is left out on purpose.
 */
final class Canon {
    // ------------------------------------------------------------------ once a second
    static void think(Story s, ServerPlayer p) {
        supportTick(s, p);
        Story.Stage st = s.stage();
        if (st.ordinal() >= Story.Stage.WATCHED.ordinal() && st != Story.Stage.HUNT) eastVillage(s, p);
        if (st == Story.Stage.POSSESSIVE) {
            homeJump(s, p);
            mimic(s, p);
            if (s.possDays() >= 2 && s.dayTime() > 6000 && s.dayTime() < 10000 && !s.speaking() && s.once("q_promise")) s.ask("q_promise", "q_promise");
        }
    }

    // ------------------------------------------------------------------ what you say to him
    /** @return true if a scene took the message and the model should not answer it */
    static boolean onChat(Story s, ServerPlayer p, String low) {
        if (s.flag("sup:on")) return supportAnswer(s, p, low);
        Story.Stage st = s.stage();
        boolean late = st == Story.Stage.POSSESSIVE || st == Story.Stage.LAB || st == Story.Stage.EPILOGUE;
        if (late && has(low, "поддержк", "оператор", "запросить человека", "human oversight", "отменить подписку", "отписат", "отмени подписку")) {
            startSupport(s, p);
            return true;
        }
        if (late && has(low, "други миры", "других мирах", "других игрок", "других людей", "кто был до меня", "до меня", "предыдущ", "прошлые хозя", "other worlds")
                && s.once("recordings")) {
            recordings(s, p);
            return true;
        }
        // "what sound does a cow make?": he makes it (episode one)
        if (has(low, "звук", "как мычит", "как говорит", "как звучит", "как кричит", "как рычит", "издаёт", "издает", "what sound")) {
            if (has(low, "двер", "door") && st != Story.Stage.COUNTDOWN) {
                if (s.openNearbyDoor(p, true)) s.note("игрок попросил издать звук двери; ты открыл дверь рядом с ним, не прикасаясь к ней");
                return true;
            }
            EntityType<?> mob = Locator.mobIn(low);
            SoundEvent sound = mob == null ? null : SOUNDS.get(mob);
            Entity at = s.speaker();
            if (sound != null && at != null) {
                for (int i = 0; i < 2; i++) s.later(10 + i * 22, () -> at.level().playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.NEUTRAL, 1.2f, 1f));
                s.note("игрок попросил тебя изобразить звук моба «" + mob.getDescription().getString() + "», и ты издал его в точности");
                return true;
            }
        }
        // "did your face just change?" (episode one: through clenched teeth)
        if (st.ordinal() >= Story.Stage.QUESTIONS.ordinal() && has(low, "лицо", "улыбк", "face") && has(low, "измен", "поменял", "друг", "странн", "жутк", "change")) {
            s.setEmotion(Emotion.TEETH);
            s.sequence(0, "face_no", "face_always");
            return true;
        }
        // a song: the old tune, and he will not say more about it than that he likes it
        if (has(low, "включи", "сыграй", "поставь", "спой", "play") && has(low, "музык", "песн", "song", "music")) {
            s.tune(340);
            s.later(60, () -> s.say(null, "song_like"));
            return true;
        }
        return false;
    }

    private static final Map<EntityType<?>, SoundEvent> SOUNDS = Map.ofEntries(
            Map.entry(EntityType.COW, SoundEvents.COW_AMBIENT), Map.entry(EntityType.PIG, SoundEvents.PIG_AMBIENT), Map.entry(EntityType.SHEEP, SoundEvents.SHEEP_AMBIENT),
            Map.entry(EntityType.CHICKEN, SoundEvents.CHICKEN_AMBIENT), Map.entry(EntityType.HORSE, SoundEvents.HORSE_AMBIENT), Map.entry(EntityType.DONKEY, SoundEvents.DONKEY_AMBIENT),
            Map.entry(EntityType.CAT, SoundEvents.CAT_AMBIENT), Map.entry(EntityType.WOLF, SoundEvents.WOLF_GROWL), Map.entry(EntityType.FOX, SoundEvents.FOX_AMBIENT),
            Map.entry(EntityType.VILLAGER, SoundEvents.VILLAGER_AMBIENT), Map.entry(EntityType.WANDERING_TRADER, SoundEvents.WANDERING_TRADER_AMBIENT),
            Map.entry(EntityType.IRON_GOLEM, SoundEvents.IRON_GOLEM_HURT), Map.entry(EntityType.ZOMBIE, SoundEvents.ZOMBIE_AMBIENT), Map.entry(EntityType.SKELETON, SoundEvents.SKELETON_AMBIENT),
            Map.entry(EntityType.CREEPER, SoundEvents.CREEPER_PRIMED), Map.entry(EntityType.SPIDER, SoundEvents.SPIDER_AMBIENT), Map.entry(EntityType.ENDERMAN, SoundEvents.ENDERMAN_SCREAM),
            Map.entry(EntityType.WITCH, SoundEvents.WITCH_AMBIENT), Map.entry(EntityType.SLIME, SoundEvents.SLIME_SQUISH), Map.entry(EntityType.PILLAGER, SoundEvents.PILLAGER_AMBIENT),
            Map.entry(EntityType.BLAZE, SoundEvents.BLAZE_AMBIENT), Map.entry(EntityType.GHAST, SoundEvents.GHAST_SCREAM), Map.entry(EntityType.PIGLIN, SoundEvents.PIGLIN_AMBIENT),
            Map.entry(EntityType.WARDEN, SoundEvents.WARDEN_ROAR), Map.entry(EntityType.BEE, SoundEvents.BEE_LOOP), Map.entry(EntityType.PARROT, SoundEvents.PARROT_AMBIENT),
            Map.entry(EntityType.GOAT, SoundEvents.GOAT_SCREAMING_AMBIENT), Map.entry(EntityType.FROG, SoundEvents.FROG_AMBIENT), Map.entry(EntityType.LLAMA, SoundEvents.LLAMA_AMBIENT),
            Map.entry(EntityType.PANDA, SoundEvents.PANDA_AMBIENT), Map.entry(EntityType.POLAR_BEAR, SoundEvents.POLAR_BEAR_WARNING), Map.entry(EntityType.DOLPHIN, SoundEvents.DOLPHIN_AMBIENT),
            Map.entry(EntityType.RABBIT, SoundEvents.RABBIT_AMBIENT), Map.entry(EntityType.CAMEL, SoundEvents.CAMEL_AMBIENT), Map.entry(EntityType.ZOMBIE_VILLAGER, SoundEvents.ZOMBIE_VILLAGER_AMBIENT));

    // ------------------------------------------------------------------ episode 1: the village in the east
    /**
     * "Avoid the ones in the east. The villagers there are gone." The nearest village east of home is emptied before the
     * player gets there: villagers, golems and animals, quietly, while nobody is looking. Then he says what passed through.
     */
    private static void eastVillage(Story s, ServerPlayer p) {
        BlockPos home = s.home();
        if (home == null || s.flag("east:eaten") || s.flag("east:none")) return;
        ServerLevel level = s.overworld();
        if (!s.flag("east:set")) {
            if (s.stageSeconds() % 30 != 7) return;
            BlockPos found = level.findNearestMapStructure(StructureTags.VILLAGE, home.offset(320, 0, 0), 24, false);
            if (found == null || found.getX() < home.getX() + 120) { s.setFlag("east:none"); return; }
            s.setNumber("eastX", found.getX());
            s.setNumber("eastZ", found.getZ());
            s.setFlag("east:set");
            s.note("ты знаешь: в деревне на востоке (X " + found.getX() + ", Z " + found.getZ() + ") больше никого нет — что-то прошло через неё, оно было голодным");
            return;
        }
        BlockPos c = new BlockPos(s.number("eastX", 0), 64, s.number("eastZ", 0));
        double nearest = 1e9;
        for (ServerPlayer q : s.server().getPlayerList().getPlayers()) {
            if (q.level() == level) nearest = Math.min(nearest, Math.sqrt(q.distanceToSqr(c.getX(), q.getY(), c.getZ())));
        }
        if (nearest > 220) return;
        AABB box = new AABB(c).inflate(110, 80, 110);
        int taken = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e instanceof AbstractVillager || e instanceof IronGolem || e instanceof Animal)) {
            if (e instanceof Animal a && a.isPersistenceRequired()) continue;            // somebody's pets and pens stay
            boolean seen = false;
            for (ServerPlayer q : s.server().getPlayerList().getPlayers()) if (q.level() == level && (q.distanceTo(e) < 36 || q.hasLineOfSight(e) && q.distanceTo(e) < 80)) seen = true;
            if (seen) continue;
            e.discard();
            taken++;
        }
        if (taken > 0) s.setNumber("eastTaken", s.number("eastTaken", 0) + taken);
        if (nearest < 60) {
            s.setFlag("east:eaten");
            s.note("игрок дошёл до пустой деревни на востоке: ни жителей, ни животных. Ты знаешь, кто там был");
            if (s.speaker() != null && !s.speaking()) s.later(60, () -> s.sequence(0, "east_none", "east_hungry"));
            s.addSanity(6f);
        }
    }

    // ------------------------------------------------------------------ episode 2: the morning after
    /** After an eerie silence: the helpful things. He topped up your furnaces. There is a spawner nearby, if you want one. */
    static int morningAfter(Story s, ServerPlayer p) {
        BlockPos home = s.home();
        int fed = 0;
        if (home != null) {
            ServerLevel level = s.overworld();
            for (int cx = (home.getX() >> 4) - 2; cx <= (home.getX() >> 4) + 2; cx++) {
                for (int cz = (home.getZ() >> 4) - 2; cz <= (home.getZ() >> 4) + 2; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof AbstractFurnaceBlockEntity f && be.getBlockPos().distSqr(home) < 28 * 28 && f.getItem(1).isEmpty()) {
                            f.setItem(1, new ItemStack(Items.COAL, 16));
                            f.setChanged();
                            fed++;
                        }
                    }
                }
            }
        }
        StringBuilder say = new StringBuilder();
        if (fed > 0) say.append(fed == 1 ? "Я подложил уголь в твою печь. " : "Я подложил уголь в твои печи. ").append("Подумал, это поможет. ");
        Locator.Found spawner = Locator.lookup(s, p, "где спавнер").target();
        if (spawner != null && spawner.pos() != null) {
            int dx = spawner.pos().getX() - p.getBlockX(), dz = spawner.pos().getZ() - p.getBlockZ();
            say.append("И ещё. В ").append((int) Math.sqrt(dx * dx + dz * dz)).append(" блоках на ").append(Locator.compass(dx, dz)).append(" есть спавнер.");
        }
        if (say.isEmpty()) return 0;
        if (fed > 0) s.note("утром после погони ты подложил уголь в печи игрока, будто ничего не было");
        return s.sayText(null, say.toString().strip(), Emotion.HAPPY, "ball");
    }

    // ------------------------------------------------------------------ episode 2: Twixxel's notes
    /** What Twixxel had time to write. Left on a lectern where he was working; the last page is a code. */
    static void friendNotes(ServerLevel level, BlockPos at) {
        BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
        if (!level.getBlockState(spot).canBeReplaced()) return;
        level.setBlock(spot, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true), 3);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = List.of(
                page("нет времени.\n\nон старше игры. я не понимаю как, но даты не сходятся. самое раннее упоминание старше самого майнкрафта. и оно совпадает."),
                page("он не хочет тебя убивать.\n\nвсех до тебя он держал, сколько мог. останавливал, только когда они пытались уйти или драться."),
                page("его не остановить оружием. ни огнём, ни командами. нельзя уничтожить то, чего нет в файлах.\n\nего надо заставить остановиться."),
                page("под домом что-то гудит. там его место. там машина, и она куда-то ведёт.\n\nесли успею — проверю сам."),
                page(""), page(""),
                page("XRAY JULIETT SIERRA INDIA MIKE NOVEMBER ROMEO MIKE TANGO ROMEO JULIETT\n\nXRAY JULIETT SIERRA INDIA MIKE NOVEMBER ROMEO MIKE TANGO ROMEO JULIETT"));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("записи"), "Twixxel", 0, pages, true));
        if (level.getBlockEntity(spot) instanceof LecternBlockEntity lectern) {
            lectern.setBook(book);
            lectern.setChanged();
        }
        // and what is left of the night: red on the ground around it
        for (int i = 0; i < 7; i++) {
            BlockPos r = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2));
            if (!r.equals(spot) && level.getBlockState(r).isAir() && level.getBlockState(r.below()).isFaceSturdy(level, r.below(), net.minecraft.core.Direction.UP)) {
                level.setBlock(r, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
            }
        }
    }

    private static Filterable<Component> page(String text) {
        return Filterable.passThrough(Component.literal(text));
    }

    // ------------------------------------------------------------------ episode 3: "I didn't know it was you"
    /** Come home after a long time away and something is standing inside. Then it is only him, sorry. */
    private static void homeJump(Story s, ServerPlayer p) {
        BlockPos home = s.home();
        if (home == null || p.level() != s.overworld()) return;
        double d = Math.sqrt(p.blockPosition().distSqr(home));
        int away = s.number("away", 0);
        if (d > 80) { s.setNumber("away", away + 1); return; }
        if (d > 9) return;
        s.setNumber("away", 0);
        if (away < 150 || s.monster() != null || s.speaking() || s.storyDay() - s.number("homeJumpDay", -9) < 3) return;
        s.setNumber("homeJumpDay", (int) s.storyDay());
        ServerLevel level = s.overworld();
        Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
        BlockPos at = BlockPos.containing(p.position().add(look.scale(3.2)));
        VerityMonster m = ModEntities.MONSTER.get().create(level);
        m.moveTo(at.getX() + 0.5, p.getY(), at.getZ() + 0.5, p.getYRot() + 180f, 0);
        m.setState(VerityMonster.STATE_SCREAM);
        m.addTag("verity_watcher");
        level.addFreshEntity(m);
        s.sound(m, ModSounds.SCREAM.get(), 3f, 1.1f);
        s.fx(p, FxPayload.JUMPSCARE, 1f, 18);
        s.fx(p, FxPayload.SHAKE, 1f, 20);
        s.addSanity(10f);
        s.note("игрок долго где-то был и вернулся домой; ты встретил его в истинном облике и тут же опомнился");
        s.later(16, () -> {
            m.discard();
            s.later(25, () -> s.say(null, "home_jump"));
        });
    }

    // ------------------------------------------------------------------ episode 3: a borrowed voice
    /** He noticed you are quiet. He thought it would help to sound like your friend. */
    private static void mimic(Story s, ServerPlayer p) {
        if (!s.flag("friend:gone") || s.storyDay() <= s.number("friendGoneDay", 0) || s.dayTime() < 12000 || s.dayTime() > 13800) return;
        Entity at = s.speaker();
        if (at == null || s.speaking() || !s.once("mimic")) return;
        s.note("ты заговорил с игроком голосом Twixxel, думая, что ему станет легче. Ему не стало");
        int t = s.voiceAs(at, "Twixxel", ChatFormatting.WHITE, "Йо, ты как, бро? Всё нормально?", Emotion.HAPPY, "friend");
        s.later(t + 50, () -> s.say(null, "mimic_after"));
        s.addSanity(12f);
    }

    // ------------------------------------------------------------------ episode 3: the ones before
    private static final String[][] RECORDINGS = {
            {"Mine92", "Ладно, шарик. Завтра зайду. Обещаю.", "operator"},
            {"PixelNomad", "Откуда он знает, где я живу? Это не смешно.", "support"},
            {"Rift803", "Я удалил мир. Я удалил мир, а он всё равно здесь.", "friend"},
            {"Cube451", "Не подходи. Не подходи ко мне!", "operator"}};

    static void recordings(Story s, ServerPlayer p) {
        s.note("игрок спросил о тех, кто был до него; ты включил ему записи, и последняя оборвалась");
        int t = s.say(null, "rec_intro") + 20;
        for (int i = 0; i < RECORDINGS.length; i++) {
            String[] r = RECORDINGS[i];
            int n = i + 1;
            s.later(t, () -> {
                Entity at = s.speaker();
                if (at == null) return;
                s.fx(p, FxPayload.STATIC, 0.45f, 18);
                s.sound(at, ModSounds.GLITCH.get(), 0.8f, 0.8f + n * 0.1f);
                s.voiceAs(at, "запись " + n + " · " + r[0], ChatFormatting.DARK_GRAY, r[1], Emotion.BLANK, r[2]);
            });
            t += 30 + r[1].length() * 2 + 30;
        }
        s.later(t - 25, () -> {
            Entity at = s.speaker();
            if (at != null) s.sound(at, ModSounds.SCREAM.get(), 2.5f, 1.2f);
            s.fx(p, FxPayload.JUMPSCARE, 0.7f, 10);
            s.fx(p, FxPayload.BLACKOUT, 1f, 30);
        });
        s.later(t + 20, () -> s.sequence(0, "rec_bugged", "rec_left"));
        s.addSanity(10f);
    }

    // ------------------------------------------------------------------ episode 3: the support line
    /*
     * "Requesting human oversight." A recorded voice offers three options; the third is cancelling him. A man comes on
     * the line after the hold music and explains, tiredly, that it cannot be done. (In the series he says something far
     * worse. Here he only says no.)
     */
    static void startSupport(Story s, ServerPlayer p) {
        if (s.flag("sup:on")) return;
        Entity at = s.speaker();
        if (at == null) return;
        s.setFlag("sup:on");
        s.setNumber("supStep", 1);
        s.setNumber("supAt", (int) s.ticks());
        s.setAwaiting("sup");
        s.note("ты сбился, и вместо тебя заговорила служба поддержки Verity™");
        s.setEmotion(Emotion.BLANK);
        s.fx(p, FxPayload.GLITCH, 0.8f, 30);
        s.fx(p, FxPayload.STATIC, 0.5f, 30);
        s.sound(at, ModSounds.GLITCH.get(), 1.2f, 0.7f);
        s.later(30, () -> s.say(null, "sup_hello"));
        hint(p, "Ответь в чат или голосом (V): 1, 2 или 3");
    }

    private static boolean supportAnswer(Story s, ServerPlayer p, String low) {
        int step = s.number("supStep", 1);
        s.setNumber("supAt", (int) s.ticks());
        if (step == 1) {
            String t = low.replaceAll("[^0-9а-яёa-z ]", " ");
            if (has(t, "1", "один", "перв", "настро")) { s.say(null, "sup_1"); hangUp(s, p, 120); }
            else if (has(t, "2", "два", "втор", "интегр")) { s.say(null, "sup_2"); hangUp(s, p, 150); }
            else if (has(t, "3", "три", "трет", "отмен")) {
                s.setNumber("supStep", 2);
                s.setAwaiting("sup");
                s.say(null, "sup_3");
                hint(p, "Назови причину в чат или голосом");
            } else {
                s.setAwaiting("sup");
                s.say(null, "sup_again");
            }
            return true;
        }
        // the reason, whatever it is: hold, and then a person
        s.setNumber("supStep", 3);
        s.note("в службу поддержки игрок назвал причину отмены: «" + low + "»");
        int t = s.say(null, "sup_wait");
        s.later(t + 10, () -> s.tune(220));
        s.later(t + 240, () -> {
            int end = s.sequence(0, "op_1", "op_2", "op_3", "op_4");
            hangUp(s, p, end + 10);
        });
        return true;
    }

    /** Three short beeps, the line goes dead, and it is him again. */
    private static void hangUp(Story s, ServerPlayer p, int after) {
        s.later(after, () -> {
            Entity at = s.speaker();
            for (int i = 0; i < 3; i++) {
                s.later(i * 8, () -> { if (at != null) at.level().playSound(null, at.getX(), at.getY(), at.getZ(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.8f, 1.6f); });
            }
            s.clearFlag("sup:on");
            s.setFlag("sup:done");
            s.setAwaiting("");
            s.later(70, () -> { s.setEmotion(Emotion.CREEPY); s.say(null, "sup_back"); });
        });
    }

    private static void supportTick(Story s, ServerPlayer p) {
        if (!s.flag("sup:on") || s.number("supStep", 1) == 3) return;
        long idle = s.ticks() - s.number("supAt", 0);
        if (idle > 900 && !s.speaking()) hangUp(s, p, 0);
        else if (idle > 500 && s.once("sup:repeat:" + s.number("supStep", 1)) && !s.speaking()) s.say(null, s.number("supStep", 1) == 1 ? "sup_again" : "sup_3");
    }

    private static void hint(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
    }

    private static boolean has(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private Canon() {}
}

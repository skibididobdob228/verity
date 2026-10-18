package dev.verity.story;

import dev.verity.entity.Emotion;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModSounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * "Where he lives": a cold little laboratory under the player's home, built when Verity offers to show it,
 * and the finale that plays out inside. Local coordinates: the corridor runs along +X from the ladder shaft;
 * the machine room opens off its south (+Z) side, the store-room of parcels off its north side, the room of names closes its far end.
 */
public final class Lab {
    private static final String[] NAMES = {"Mine92", "PixelNomad", "Rift803", "Brick29", "Cube451", "NoxFatale", "Vex_77", "Duck390", "Mobi317",
            "Quartz8", "Boltcutter", "ShadowViper", "NovaX23", "RushB45", "Phantom7", "Karma_X9", "Pulse22", "Ember_14", "Loop404", "Tundra6",
            "GlassOwl", "Kettle88", "Moss_Kid", "Static31", "Wren2011", "Hollow_J", "Sable9", "Orbit55", "Juniper_", "Flint_03", "Marrow7",
            "Dusk_Fox", "Nettle12", "Copper_M", "Vanta_K", "Lumen27", "Ashgrove", "Pebble_X", "Thistle4", "Cinder90", "Ravel_8", "Quill_17",
            "ThatMob", "Twixxel"};

    public static void build(Story story, ServerPlayer p) {
        ServerLevel level = story.overworld();
        BlockPos home = story.home() != null ? story.home() : p.blockPosition();
        int ox = home.getX() + 9, oz = home.getZ() + 9;
        int top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(ox - 1, 0, oz)).getY();
        int fy = Math.max(level.getMinBuildHeight() + 6, top - 26);
        boolean bunker = top - fy < 8;              // no room underground (superflat): build it as a bunker on the surface
        if (bunker) fy = top - 1;
        dev.verity.VerityMod.LOGGER.info("Verity lab: origin {} {} {}, surface {}, {}", ox, fy, oz, top, bunker ? "bunker" : "buried");
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), floor = Blocks.SMOOTH_STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();

        // corridor
        fill(level, ox - 3, fy, oz - 2, ox + 23, fy + 4, oz + 2, wall);
        fill(level, ox - 1, fy, oz - 1, ox + 22, fy, oz + 1, floor);
        fill(level, ox - 1, fy, oz, ox + 22, fy, oz, Blocks.YELLOW_CONCRETE.defaultBlockState());
        fill(level, ox - 1, fy + 1, oz - 1, ox + 22, fy + 3, oz + 1, air);
        for (int x : new int[] {3, 11, 19}) level.setBlock(new BlockPos(ox + x, fy + 4, oz), Blocks.SEA_LANTERN.defaultBlockState(), 3);

        if (bunker) {
            fill(level, ox - 3, fy + 1, oz, ox - 2, fy + 2, oz, air);
        } else {
            shaft(level, ox, fy, oz, top);
        }
        buildRooms(level, p, ox, fy, oz, wall, floor, air);
        story.setLab(new BlockPos(ox, fy, oz));
        story.setNumber("labTop", top);
        if (bunker) story.setFlag("labBunker"); else story.clearFlag("labBunker");
        for (String f : new String[] {"lab:entered", "lab:victims", "lab:machine", "lab:lever", "lab:choice", "lab:sealing", "lab:boxes"}) story.clearFlag(f);
        p.sendSystemMessage(Component.literal("Верити показывает координаты: X " + (ox - 1) + ", Z " + oz + (bunker ? " — серый бункер рядом с домом." : " — люк в земле рядом с домом."))
                .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
    }

    /** Ladder shaft up to a hatch on the surface. */
    private static void shaft(ServerLevel level, int ox, int fy, int oz, int top) {
        BlockState air = Blocks.AIR.defaultBlockState();
        fill(level, ox - 2, fy + 4, oz - 1, ox, top - 1, oz + 1, Blocks.STONE_BRICKS.defaultBlockState());
        fill(level, ox - 1, fy + 1, oz, ox - 1, top + 1, oz, air);
        for (int y = fy + 1; y <= top - 1; y++) {
            level.setBlock(new BlockPos(ox - 2, y, oz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(ox - 1, y, oz), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST), 3);
        }
        fill(level, ox - 2, top - 1, oz - 1, ox, top - 1, oz + 1, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        level.setBlock(new BlockPos(ox - 1, top - 1, oz), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST), 3);
        level.setBlock(new BlockPos(ox - 2, top - 1, oz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(new BlockPos(ox - 2, top, oz), Blocks.POLISHED_DEEPSLATE_WALL.defaultBlockState(), 3);
        level.setBlock(new BlockPos(ox - 2, top + 1, oz), Blocks.SOUL_LANTERN.defaultBlockState(), 3);
    }

    private static void buildRooms(ServerLevel level, ServerPlayer p, int ox, int fy, int oz, BlockState wall, BlockState floor, BlockState air) {
        // machine room
        fill(level, ox + 5, fy, oz + 2, ox + 16, fy + 6, oz + 13, wall);
        fill(level, ox + 6, fy, oz + 3, ox + 15, fy, oz + 12, floor);
        fill(level, ox + 6, fy + 1, oz + 3, ox + 15, fy + 5, oz + 12, air);
        fill(level, ox + 10, fy + 1, oz + 2, ox + 11, fy + 2, oz + 2, air);
        fill(level, ox + 9, fy + 1, oz + 12, ox + 12, fy + 4, oz + 12, Blocks.IRON_BLOCK.defaultBlockState());
        fill(level, ox + 10, fy + 2, oz + 12, ox + 11, fy + 3, oz + 12, Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        level.setBlock(new BlockPos(ox + 9, fy + 5, oz + 12), Blocks.LIGHTNING_ROD.defaultBlockState(), 3);
        level.setBlock(new BlockPos(ox + 12, fy + 5, oz + 12), Blocks.LIGHTNING_ROD.defaultBlockState(), 3);
        level.setBlock(new BlockPos(ox + 10, fy + 6, oz + 7), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        level.setBlock(leverPos(ox, fy, oz).below(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
        level.setBlock(leverPos(ox, fy, oz), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);

        // the room of names
        fill(level, ox + 23, fy, oz - 5, ox + 33, fy + 5, oz + 5, Blocks.DEEPSLATE_TILES.defaultBlockState());
        fill(level, ox + 24, fy + 1, oz - 4, ox + 32, fy + 4, oz + 4, air);
        fill(level, ox + 23, fy + 1, oz, ox + 23, fy + 2, oz, air);
        level.setBlock(new BlockPos(ox + 28, fy + 5, oz), Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
        level.setBlock(new BlockPos(ox + 28, fy + 4, oz), Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
        level.setBlock(new BlockPos(ox + 28, fy + 5, oz), Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
        for (int[] c : new int[][] {{25, -3}, {25, 3}, {31, -3}, {31, 3}}) {
            level.setBlock(new BlockPos(ox + c[0], fy + 1, oz + c[1]), Blocks.SOUL_LANTERN.defaultBlockState(), 3);
        }
        List<String> names = new ArrayList<>(List.of(NAMES));
        Collections.shuffle(names, new Random(ox * 31L + oz));
        int n = 0;
        for (int row = 0; row < 2; row++) {
            int y = fy + 2 + row;
            for (int x = ox + 24; x <= ox + 32; x++) {
                sign(level, new BlockPos(x, y, oz - 4), Direction.SOUTH, names.get(n++ % names.size()) + " - X", false);
                sign(level, new BlockPos(x, y, oz + 4), Direction.NORTH, names.get(n++ % names.size()) + " - X", false);
            }
            for (int z = oz - 3; z <= oz + 3; z++) {
                if (z == oz && row == 0) continue;
                sign(level, new BlockPos(ox + 32, y, z), Direction.WEST, names.get(n++ % names.size()) + " - X", false);
            }
        }
        sign(level, new BlockPos(ox + 32, fy + 2, oz), Direction.WEST, p.getGameProfile().getName() + " -", true);

        // the store-room: parcels packed and addressed, waiting for whoever comes after you
        fill(level, ox + 12, fy, oz - 9, ox + 19, fy + 4, oz - 2, wall);
        fill(level, ox + 13, fy, oz - 8, ox + 18, fy, oz - 3, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        fill(level, ox + 13, fy + 1, oz - 8, ox + 18, fy + 3, oz - 3, air);
        fill(level, ox + 15, fy + 1, oz - 2, ox + 15, fy + 2, oz - 2, air);
        level.setBlock(new BlockPos(ox + 15, fy + 4, oz - 5), Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
        level.setBlock(new BlockPos(ox + 15, fy + 3, oz - 5), Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
        level.setBlock(new BlockPos(ox + 15, fy + 4, oz - 5), wall, 3);
        net.minecraft.world.phys.AABB room = new net.minecraft.world.phys.AABB(ox + 12, fy, oz - 9, ox + 20, fy + 5, oz - 1);
        for (dev.verity.entity.VerityBox old : level.getEntitiesOfClass(dev.verity.entity.VerityBox.class, room, dev.verity.entity.VerityBox::isDecoy)) old.discard();
        Random rnd = new Random(ox * 17L + oz);
        for (int[] c : new int[][] {{13, -8}, {15, -8}, {17, -8}, {18, -6}, {13, -6}, {18, -4}, {13, -4}}) {
            dev.verity.entity.VerityBox box = ModEntities.BOX.get().create(level);
            box.setDecoy(true);
            box.moveTo(ox + c[0] + 0.5, fy + 1.02, oz + c[1] + 0.5, rnd.nextInt(8) * 45f, 0f);
            level.addFreshEntity(box);
        }
        sign(level, new BlockPos(ox + 14, fy + 3, oz - 8), Direction.SOUTH, "ДЛЯ: GroxMC", false);
        sign(level, new BlockPos(ox + 16, fy + 3, oz - 8), Direction.SOUTH, "ДЛЯ: " + NAMES[Math.floorMod(ox * 7 + oz, NAMES.length - 2)], false);
        sign(level, new BlockPos(ox + 18, fy + 3, oz - 5), Direction.WEST, "ДЛЯ: ___", false);

        // the staff room: lockers, and a whiteboard of notes from the people who used to look after him
        fill(level, ox + 1, fy, oz - 9, ox + 11, fy + 4, oz - 2, wall);
        fill(level, ox + 2, fy, oz - 8, ox + 10, fy, oz - 3, floor);
        fill(level, ox + 2, fy + 1, oz - 8, ox + 10, fy + 3, oz - 3, air);
        fill(level, ox + 6, fy + 1, oz - 2, ox + 6, fy + 2, oz - 2, air);
        level.setBlock(new BlockPos(ox + 6, fy + 4, oz - 5), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        fill(level, ox + 2, fy + 1, oz - 8, ox + 2, fy + 2, oz - 4, Blocks.BARREL.defaultBlockState());          // the lockers
        fill(level, ox + 3, fy + 1, oz - 9, ox + 9, fy + 3, oz - 9, Blocks.WHITE_CONCRETE.defaultBlockState());   // the whiteboard
        for (int i = 0; i < WHITEBOARD.length; i++) {
            notice(level, new BlockPos(ox + 3 + i % 7, fy + 3 - i / 7, oz - 8), Direction.SOUTH, WHITEBOARD[i], DyeColor.BLACK, false);
        }
        notice(level, new BlockPos(ox + 9, fy + 2, oz - 8), Direction.SOUTH, new String[] {"НУЖЕН", "РУКОВОДИТЕЛЬ?", "ЗАПРОСИТЬ", "ЧЕЛОВЕКА"}, DyeColor.YELLOW, true);
    }

    /** Written on the whiteboard in the staff room, by people who are not here any more. */
    private static final String[][] WHITEBOARD = {
            {"Кто подписал", "после четверга?", "— Отдел кадров.", "— Я не об этом."},
            {"Записи", "забрали.", "Они всегда", "забирают."},
            {"Сколько стоят", "его данные?", "— Миллиарды.", ""},
            {"Что было", "в Лаборатории 4?", "— Спроси уборщ.", "— Уволился."},
            {"Кто всё время", "прощается", "перед уходом?!", "Опять калибруем"},
            {"Почему он всё", "спрашивает,", "когда мы придём", "завтра?"},
            {"Обновите его", "до сборки 7.4.2", "", ""},
            {"Кто дежурит", "до меня?", "— Э-э-э...", ""}};

    private static void notice(ServerLevel level, BlockPos pos, Direction facing, String[] lines, DyeColor color, boolean glowing) {
        level.setBlock(pos, (glowing ? Blocks.BAMBOO_WALL_SIGN : Blocks.BIRCH_WALL_SIGN).defaultBlockState().setValue(WallSignBlock.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity be) {
            SignText t = new SignText();
            for (int i = 0; i < 4 && i < lines.length; i++) t = t.setMessage(i, Component.literal(lines[i]));
            t = t.setColor(color);
            if (glowing) t = t.setHasGlowingText(true);
            be.setText(t, true);
            be.setChanged();
        }
    }

    private static BlockPos leverPos(int ox, int fy, int oz) { return new BlockPos(ox + 10, fy + 2, oz + 8); }
    private static BlockPos stayPos(BlockPos o) { return new BlockPos(o.getX() + 8, o.getY() + 2, o.getZ() + 6); }
    private static BlockPos sealPos(BlockPos o) { return new BlockPos(o.getX() + 13, o.getY() + 2, o.getZ() + 6); }

    private static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) level.setBlock(pos, state, 2);
    }

    private static void sign(ServerLevel level, BlockPos pos, Direction facing, String text, boolean glowing) {
        level.setBlock(pos, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity be) {
            SignText t = new SignText().setMessage(1, Component.literal(text));
            if (glowing) t = t.setColor(DyeColor.YELLOW).setHasGlowingText(true);
            be.setText(t, true);
            be.setChanged();
        }
    }

    // ------------------------------------------------------------------ the finale
    static void think(Story story, ServerPlayer p) {
        BlockPos o = story.labPos();
        if (o == null || p.level() != story.overworld()) return;
        double rx = p.getX() - o.getX(), ry = p.getY() - o.getY(), rz = p.getZ() - o.getZ();
        boolean below = ry > 0.5 && ry < 6;
        boolean corridor = below && rx > -1.6 && rx < 23.2 && Math.abs(rz - 0.5) < 2;
        boolean machine = below && rz > 2.6 && rx > 5.5 && rx < 16.5;
        boolean names = below && rx >= 23.6;
        boolean boxes = below && rz < -1.6 && rx > 12.6 && rx < 19.4;
        boolean staff = below && rz < -1.6 && rx > 1.6 && rx < 10.4;
        boolean inside = corridor || machine || names || boxes || staff;
        if (!story.flag("lab:sealing")) story.setAtmosphere(inside ? 0.45f : 0.1f, inside ? 0.6f : 0.3f, false);
        if (inside && story.setFlag("lab:entered")) { story.note("игрок спустился в твою лабораторию"); story.say(null, "lab_home"); }
        if (names && !story.speaking() && story.setFlag("lab:victims")) {
            story.note("игрок вошёл в комнату с именами тех, кто был до него, хотя ты просил не заходить; ты сорвался и тут же извинился");
            int t = story.say(null, "lab_victims");
            story.fx(p, FxPayload.GLITCH, 0.7f, 30);
            story.later(t + 30, () -> outburst(story, p));
        }
        if (staff && !story.speaking() && story.setFlag("lab:staff")) {
            story.note("игрок зашёл в комнату персонала с доской записок; там записка «ЗАПРОСИТЬ ЧЕЛОВЕКА»");
            int t = story.say(null, "lab_staff");
            story.later(t + 160, () -> { if (!story.speaking()) story.say(null, "lab_note"); });
            story.later(t + 420, () -> story.setFlag("sup:pending"));      // the call comes through at the next quiet moment
        }
        if (machine && !story.speaking() && story.setFlag("lab:machine")) story.say(null, "lab_machine");
        if (story.flag("sup:pending") && !story.speaking() && story.speaker() != null && !story.flag("lab:lever") && !story.flag("lab:sealing")) {
            story.clearFlag("sup:pending");
            if (!story.flag("sup:done")) Canon.startSupport(story, p);
        }
        if (boxes && !story.speaking() && story.setFlag("lab:boxes")) {
            story.note("игрок нашёл в лаборатории комнату с запакованными посылками для следующих игроков");
            story.sequence(10, "lab_boxes", "lab_boxes_2");
        }
    }

    /** Episode 3: the thing breaks in, nearly takes you, and is a ball again, apologising, then talking about ore. */
    private static void outburst(Story story, ServerPlayer p) {
        ServerLevel level = story.overworld();
        BlockPos o = story.labPos();
        if (o == null) return;
        VerityMonster m = ModEntities.MONSTER.get().create(level);
        Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
        m.moveTo(p.getX() + look.x * 2.6, o.getY() + 1, p.getZ() + look.z * 2.6, p.getYRot() + 180f, 0);
        m.setState(VerityMonster.STATE_SCREAM);
        m.addTag("verity_watcher");
        level.addFreshEntity(m);
        story.sound(m, ModSounds.SCREAM.get(), 4f, 1f);
        story.fx(p, FxPayload.JUMPSCARE, 1f, 20);
        story.fx(p, FxPayload.SHAKE, 1f, 40);
        story.later(26, () -> {
            m.discard();
            int t = story.sequence(10, "lab_sorry", "lab_victims_2");
            story.later(t + 30, () -> {
                dev.verity.ai.Locator.Found iron = dev.verity.ai.Locator.lookup(story, p, "где железо").target();
                if (iron != null && iron.pos() != null && !story.speaking()) {
                    story.sayText(null, "Кстати. Тут рядом железо: X " + iron.pos().getX() + ", Y " + iron.pos().getY() + ", Z " + iron.pos().getZ() + ". Тебе ведь нужно железо?",
                            Emotion.HAPPY, "ball");
                }
            });
        });
    }

    /** Lever and the two buttons. @return true if the click was one of ours */
    public static boolean onUse(Story story, ServerPlayer p, BlockPos pos) {
        BlockPos o = story.labPos();
        if (o == null || story.stage() != Story.Stage.LAB) return false;
        if (pos.equals(leverPos(o.getX(), o.getY(), o.getZ())) && story.setFlag("lab:lever")) {
            startMachine(story, p);
            return true;
        }
        if (!story.flag("lab:choice") || story.flag("lab:sealing")) return false;
        if (pos.equals(stayPos(o)) || pos.equals(stayPos(o).below())) { stay(story, p); return true; }
        if (pos.equals(sealPos(o)) || pos.equals(sealPos(o).below())) { seal(story, p); return true; }
        return false;
    }

    static boolean onChat(Story story, ServerPlayer p, String low) {
        if (!story.flag("lab:choice") || story.flag("lab:sealing")) return false;
        if (low.contains("остан") || low.contains("останусь") || low.contains("stay")) { stay(story, p); return true; }
        if (low.contains("запеч") || low.contains("ухожу") || low.contains("уйду") || low.contains("прощай")) { seal(story, p); return true; }
        return false;
    }

    /**
     * "SENDHIMHOME". The lever puts him into the machine and the machine takes him: he screams your name, and is gone.
     * For a few seconds. Then something knocks on the glass from the other side, and comes through it. The machine only
     * ever worked one way, and now he knows what you tried to do.
     */
    private static void startMachine(Story story, ServerPlayer p) {
        story.note("игрок дёрнул рычаг машины, пытаясь отправить тебя «домой»; машина работает только в одну сторону, и ты вернулся через стекло");
        ServerLevel level = story.overworld();
        BlockPos o = story.labPos();
        Vec3 core = new Vec3(o.getX() + 11, o.getY() + 3, o.getZ() + 11.5);
        story.setFlag("lab:sending");
        VerityBall b = story.materialize(p);
        b.moveTo(o.getX() + 11, o.getY() + 1.05, o.getZ() + 10.5, 0, 0);
        b.setDeltaMovement(Vec3.ZERO);
        int t = story.say(b, "lab_send");
        level.playSound(null, core.x, core.y, core.z, ModSounds.MACHINE.get(), net.minecraft.sounds.SoundSource.BLOCKS, 3f, 1f);
        story.fx(p, FxPayload.SHAKE, 0.9f, 100);
        story.fx(p, FxPayload.STATIC, 0.7f, 80);
        for (int i = 0; i < 5; i++) {
            story.later(i * 18, () -> {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, core.x, core.y, core.z, 40, 0.8, 0.8, 0.2, 0.4);
                level.sendParticles(ParticleTypes.END_ROD, core.x, core.y, core.z, 14, 0.5, 0.5, 0.1, 0.08);
                story.setCorruption(1f);
                story.setEmotion(level.random.nextBoolean() ? Emotion.SCARED : Emotion.TEETH);
            });
        }
        story.later(Math.max(t, 90), () -> {                 // gone
            VerityBall bb = story.ball();
            if (bb != null) { level.sendParticles(ParticleTypes.FLASH, bb.getX(), bb.getY() + 0.5, bb.getZ(), 1, 0, 0, 0, 0); bb.discard(); }
            story.fx(p, FxPayload.FLASH, 1f, 10);
            story.setAtmosphere(0.2f, 0.2f, false);
        });
        int back = Math.max(t, 90) + 150;                    // a long quiet, and then the glass
        for (int i = 0; i < 3; i++) {
            story.later(back + i * 14, () -> level.playSound(null, core.x, core.y, core.z, ModSounds.KNOCK.get(), net.minecraft.sounds.SoundSource.HOSTILE, 2.5f, 0.7f));
        }
        story.later(back + 60, () -> {
            for (BlockPos g : BlockPos.betweenClosed(o.getX() + 10, o.getY() + 2, o.getZ() + 12, o.getX() + 11, o.getY() + 3, o.getZ() + 12)) level.destroyBlock(g, false);
            VerityMonster m = ModEntities.MONSTER.get().create(level);
            m.moveTo(o.getX() + 11, o.getY() + 1, o.getZ() + 9.5, 180f, 0);
            m.setState(VerityMonster.STATE_SCREAM);
            m.addTag("verity_watcher");
            level.addFreshEntity(m);
            story.sound(m, ModSounds.SCREAM.get(), 5f, 1f);
            story.fx(p, FxPayload.JUMPSCARE, 1f, 22);
            story.fx(p, FxPayload.SHAKE, 1f, 50);
            story.later(40, () -> {
                m.discard();
                story.clearFlag("lab:sending");
                VerityBall again = story.materialize(p);
                story.setCorruption(0.75f);
                story.setAtmosphere(0.45f, 0.6f, false);
                List<String> talk = new ArrayList<>(List.of("lab_not_send", "lab_tried", "lab_cant"));
                if (story.flag("friend:gone")) talk.addAll(List.of("lab_twix_1", "lab_twix_2"));
                talk.addAll(List.of("lab_mono_1", "lab_mono_2", "lab_mono_3", "lab_mono_4", "lab_mono_5", "lab_mono_6", "lab_die", "lab_worlds"));
                startMonologue(story, p, again, talk);
            });
        });
    }

    private static void startMonologue(Story story, ServerPlayer p, VerityBall b, List<String> talk) {
        {
            int end = story.sequence(10, talk.toArray(String[]::new));
            story.later(end + 30, () -> {
                int said = story.say(null, "lab_choice");
                story.later(said + 12, () -> { if (!story.speaking()) story.byName(p, null, "Пожалуйста, %s.", Emotion.SAD, "flat"); });
                choice(story);
            });
        }
    }

    private static void choice(Story story) {
        ServerLevel level = story.overworld();
        BlockPos o = story.labPos();
        BlockPos stay = stayPos(o), seal = sealPos(o);
        level.setBlock(stay.below(), Blocks.LIME_WOOL.defaultBlockState(), 3);
        level.setBlock(seal.below(), Blocks.RED_WOOL.defaultBlockState(), 3);
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR);
        level.setBlock(stay, button, 3);
        level.setBlock(seal, button, 3);
        standing(level, stay.below().north(), "ОСТАТЬСЯ", DyeColor.LIME);
        standing(level, seal.below().north(), "ЗАПЕЧАТАТЬ", DyeColor.RED);
        level.sendParticles(ParticleTypes.POOF, stay.getX() + 0.5, stay.getY(), stay.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.02);
        level.sendParticles(ParticleTypes.POOF, seal.getX() + 0.5, seal.getY(), seal.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.02);
        story.setFlag("lab:choice");
    }

    private static void standing(ServerLevel level, BlockPos pos, String text, DyeColor color) {
        level.setBlock(pos, Blocks.DARK_OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity be) {
            be.setText(new SignText().setMessage(1, Component.literal(text)).setColor(color).setHasGlowingText(true), true);
            be.setChanged();
        }
    }

    /** Ending one: you stay. */
    private static void stay(Story story, ServerPlayer p) {
        story.note("игрок решил остаться с тобой");
        story.clearFlag("lab:choice");
        story.setCorruption(0.1f);
        int end = story.sequence(0, "end_stay_1", "end_stay_2");
        story.later(end + 20, () -> {
            story.setEnding("stay");
            story.setStage(Story.Stage.EPILOGUE);
            story.showTitle(p, "У тебя есть я", "Конец. Он больше никогда не будет один.");
        });
    }

    /** Ending two: you try to leave him down here. He does not take it well. */
    private static void seal(Story story, ServerPlayer p) {
        story.note("игрок решил запечатать тебя под землёй и уйти");
        story.setFlag("lab:sealing");
        ServerLevel level = story.overworld();
        VerityBall b = story.materialize(p);           // he is out of your pocket before you finish pressing
        int t = story.say(b, story.flag("promised") && story.once("promise:said") ? "end_promise" : "end_seal_1");
        story.fx(p, FxPayload.SHAKE, 1f, 80);
        story.setAtmosphere(0.75f, 1f, true);
        b.setMode(VerityBall.MODE_TRANSFORM);
        story.setCorruption(1f);
        story.setEmotion(Emotion.TEETH);
        story.sound(b, ModSounds.TRANSFORM.get(), 2.5f, 1f);
        story.later(t + 10, () -> {
            VerityBall bb = story.ball();
            BlockPos o = story.labPos();
            Vec3 at = bb != null ? bb.position() : new Vec3(o.getX() + 10.5, o.getY() + 1, o.getZ() + 9.5);
            if (bb != null) bb.discard();
            VerityMonster m = ModEntities.MONSTER.get().create(level);
            m.moveTo(at.x, o.getY() + 1, at.z, 0, 0);
            m.setState(VerityMonster.STATE_RISE);
            m.setPrey(p.getUUID());
            level.addFreshEntity(m);
            story.setMonster(m);
            story.later(45, () -> {
                VerityMonster mm = story.monster();
                if (mm == null) return;
                mm.setState(VerityMonster.STATE_SCREAM);
                story.sound(mm, ModSounds.SCREAM.get(), 5f, 1f);
            });
            story.later(85, () -> { VerityMonster mm = story.monster(); if (mm != null) mm.setState(VerityMonster.STATE_HUNT); });
        });
    }

    /** Debug/showcase helpers: jump to a place in the lab, or work its controls without clicking. */
    public static void debugTeleport(Story story, ServerPlayer p, String where) {
        BlockPos o = story.labPos();
        if (o == null) return;
        double x = o.getX(), y = o.getY() + 1, z = o.getZ() + 0.5;
        float yaw = -90f;
        switch (where) {
            case "names" -> { x += 26.5; }
            case "machine" -> { x += 10.5; z += 4; yaw = 0f; }
            case "boxes" -> { x += 15.5; z -= 3; yaw = 180f; }
            case "top" -> { x -= 0.5; y = story.number("labTop", o.getY() + 26) + 0.2; }
            case "end" -> { x += 20.5; yaw = 90f; }
            case "staff" -> { x += 6.5; z -= 5; yaw = 180f; }
            default -> x += 2.5;
        }
        p.teleportTo(story.overworld(), x, y, z, yaw, 0f);
    }

    public static void debugUse(Story story, ServerPlayer p, String what) {
        BlockPos o = story.labPos();
        if (o == null) return;
        if (what.equals("choice")) {            // skip the monologue
            story.setFlag("lab:lever");
            story.say(null, "lab_choice");
            choice(story);
            return;
        }
        onUse(story, p, switch (what) {
            case "stay" -> stayPos(o);
            case "seal" -> sealPos(o);
            default -> leverPos(o.getX(), o.getY(), o.getZ());
        });
    }

    /** Every tick while in the lab: the chase up the ladder. */
    static void tick(Story story) {
        if (!story.flag("lab:sealing")) return;
        ServerPlayer p = story.ownerPlayer();
        VerityMonster m = story.monster();
        BlockPos o = story.labPos();
        if (p == null || o == null) return;
        int top = story.number("labTop", o.getY() + 26);
        ServerLevel level = story.overworld();
        boolean bunker = story.flag("labBunker");
        if ((bunker ? p.getX() < o.getX() - 3.6 : p.getY() >= top - 0.2) && p.level() == level) {
            // out. Seal the way behind you.
            if (bunker) fill(level, o.getX() - 3, o.getY() + 1, o.getZ(), o.getX() - 2, o.getY() + 2, o.getZ(), Blocks.OBSIDIAN.defaultBlockState());
            else fill(level, o.getX() - 1, top - 6, o.getZ(), o.getX() - 1, top - 1, o.getZ(), Blocks.OBSIDIAN.defaultBlockState());
            level.sendParticles(ParticleTypes.LARGE_SMOKE, o.getX() - 0.5, top, o.getZ() + 0.5, 50, 0.4, 0.4, 0.4, 0.04);
            level.playSound(null, o.getX() - 0.5, top, o.getZ() + 0.5, net.minecraft.sounds.SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.5f);
            story.clearFlag("lab:sealing");
            story.clearFlag("lab:choice");
            int t = m != null ? story.say(m, "end_seal_2") : 60;
            story.fx(p, FxPayload.SHAKE, 0.6f, 60);
            story.setEnding("sealed");
            story.later(t + 20, () -> {
                VerityMonster mm = story.monster();
                if (mm != null) { mm.discard(); story.setMonster(null); }
                story.setStage(Story.Stage.EPILOGUE);
                story.showTitle(p, "Никто не возвращается", "Конец. Под землёй очень тихо.");
            });
            return;
        }
        if (m != null && m.getState() == VerityMonster.STATE_HUNT && m.distanceTo(p) < 1.9 && !p.isCreative() && !p.isSpectator()) {
            // caught: he does not kill you here, he just will not let you leave
            story.sound(m, ModSounds.JUMPSCARE.get(), 4f, 1f);
            story.fx(p, FxPayload.JUMPSCARE, 1f, 30);
            story.fx(p, FxPayload.BLACKOUT, 1f, 90);
            story.clearFlag("lab:sealing");
            story.addAnger(1);
            m.discard();
            story.setMonster(null);
            story.later(30, () -> {
                p.teleportTo(level, o.getX() + 10.5, o.getY() + 1, o.getZ() + 5.5, 0, 0);
                story.giveTo(p);
                story.setCorruption(0.6f);
                story.setAtmosphere(0.45f, 0.6f, false);
                story.later(70, () -> { story.say(null, "lab_choice"); story.setFlag("lab:choice"); });
            });
        }
    }

    private Lab() {}
}

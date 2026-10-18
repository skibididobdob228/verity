package dev.verity.ai;

import com.mojang.datafixers.util.Pair;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityFriend;
import dev.verity.entity.VerityMonster;
import dev.verity.story.Story;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

/**
 * "Where is the nearest village?" answered the way /locate would, but in his voice. Reads the question, works out what
 * it is about (a structure, a biome, an ore or block, a creature, another player, a recipe, the player's own death or
 * bed, the seed) and finds it for real, with exact coordinates, distance and which way to turn. The model only gets to
 * phrase the answer; the numbers come from here.
 */
public final class Locator {
    /** One thing he found or checked. {@code fact} is what the model is told; pos is set when it is a place to go. */
    public record Found(String fact, @Nullable BlockPos pos, @Nullable ResourceKey<Level> dim, boolean exactY, String label) {
        Found(String fact, @Nullable BlockPos pos, @Nullable ResourceKey<Level> dim, boolean exactY) { this(fact, pos, dim, exactY, ""); }

        public String coords() {
            if (pos == null) return "";
            return "X " + pos.getX() + (exactY ? ", Y " + pos.getY() : "") + ", Z " + pos.getZ();
        }
    }

    public record Result(List<Found> found) {
        public static final Result NONE = new Result(List.of());
        public boolean isEmpty() { return found.isEmpty(); }
        /** The first place among the findings, to point the way to. */
        @Nullable public Found target() {
            for (Found f : found) if (f.pos() != null) return f;
            return null;
        }
    }

    // ------------------------------------------------------------------ what a question looks like
    /** Word boundaries that know Cyrillic is made of letters (since Java 19 a plain \\b only knows ASCII). */
    private static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static final Pattern ASKS = Pattern.compile("\\?|где|найд|найти|ищ|покаж|подскаж|скажи|далек|близк|ближайш|сколько|куда|дойти|добрат|пройти|попасть"
            + "|есть ли|координат|дорог|путь|веди|отведи|направ|where|find|locate|nearest|how far", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RECIPE = re("крафт|скрафт|рецепт|сделать|сделай|создать|изготов|собрать|из чего|получить|переплав|craft|recipe|how to make");

    // ------------------------------------------------------------------ structures
    private record S(Pattern re, String name, @Nullable ResourceKey<Level> dim, boolean underground, @Nullable TagKey<Structure> tag, List<ResourceKey<Structure>> keys) {}
    private static final List<S> STRUCTURES = new ArrayList<>();

    private static void s(String re, String name, @Nullable ResourceKey<Level> dim, boolean underground, @Nullable TagKey<Structure> tag, List<ResourceKey<Structure>> keys) {
        STRUCTURES.add(new S(re(re), name, dim, underground, tag, keys));
    }

    static {
        // the specific phrasings first: "крепость в аду" must not become the stronghold, "пустынный храм" must not become a desert
        s("(адск|незер|нижн\\S* мир)\\S* ?крепост|крепост\\S* (в )?(аду|незер|нижн)|fortress", "крепость Нижнего мира", Level.NETHER, false, null, List.of(BuiltinStructures.FORTRESS));
        s("бастион|bastion", "бастион", Level.NETHER, false, null, List.of(BuiltinStructures.BASTION_REMNANT));
        s("город\\S* (в )?(кра|энд)|энд.?сити|end.?city|корабл\\S* (в )?кра", "город Края", Level.END, false, null, List.of(BuiltinStructures.END_CITY));
        s("окаменел|fossil", "окаменелость в Нижнем мире", Level.NETHER, false, null, List.of(BuiltinStructures.NETHER_FOSSIL));
        s("подводн\\S* (крепост|храм)|монумент|monument|страж", "подводная крепость", Level.OVERWORLD, false, null, List.of(BuiltinStructures.OCEAN_MONUMENT));
        s("крепост|stronghold|портал\\S* (в )?(кра|энд)|энд.?портал|end portal|око эндера|глаз\\S* эндера", "крепость с порталом в Край", Level.OVERWORLD, true,
                StructureTags.EYE_OF_ENDER_LOCATED, List.of());
        s("аванпост|разбойник|пиллагер|outpost|pillager", "аванпост разбойников", Level.OVERWORLD, false, null, List.of(BuiltinStructures.PILLAGER_OUTPOST));
        s("особняк|мэншн|усадьб|mansion", "лесной особняк", Level.OVERWORLD, false, null, List.of(BuiltinStructures.WOODLAND_MANSION));
        s("храм\\S* (в )?джунгл|джунглев\\S* храм|jungle (temple|pyramid)", "храм в джунглях", Level.OVERWORLD, false, null, List.of(BuiltinStructures.JUNGLE_TEMPLE));
        s("пирамид|пустынн\\S* храм|храм\\S* (в )?пустын|desert (temple|pyramid)", "пустынный храм", Level.OVERWORLD, false, null, List.of(BuiltinStructures.DESERT_PYRAMID));
        s("храм|temple", "храм", Level.OVERWORLD, false, null, List.of(BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.JUNGLE_TEMPLE));
        s("хижин|избушк|ведьм|swamp hut|witch", "хижина ведьмы", Level.OVERWORLD, false, null, List.of(BuiltinStructures.SWAMP_HUT));
        s("иглу|igloo", "иглу", Level.OVERWORLD, false, null, List.of(BuiltinStructures.IGLOO));
        s("древн\\S* город|ancient.?city|варден|warden", "древний город", Level.OVERWORLD, true, null, List.of(BuiltinStructures.ANCIENT_CITY));
        s("руин\\S* троп|trail.?ruins|археолог", "руины троп", Level.OVERWORLD, false, null, List.of(BuiltinStructures.TRAIL_RUINS));
        s("испытан|trial", "испытательные залы", Level.OVERWORLD, true, null, List.of(BuiltinStructures.TRIAL_CHAMBERS));
        s("клад|сокровищ|treasure", "зарытый клад", Level.OVERWORLD, false, null, List.of(BuiltinStructures.BURIED_TREASURE));
        s("шахт|mineshaft", "заброшенная шахта", null, true, StructureTags.MINESHAFT, List.of());
        s("разрушенн\\S* портал|ruined.?portal|портал", "разрушенный портал", null, false, StructureTags.RUINED_PORTAL, List.of());
        s("корабл|кораблекрушен|shipwreck", "затонувший корабль", Level.OVERWORLD, false, StructureTags.SHIPWRECK, List.of());
        s("подводн\\S* руин|руин|ocean.?ruin", "подводные руины", Level.OVERWORLD, false, StructureTags.OCEAN_RUIN, List.of());
        s("деревн|жител|посел|посёл|village", "деревня", Level.OVERWORLD, false, StructureTags.VILLAGE, List.of());
    }

    // ------------------------------------------------------------------ biomes
    private record B(Pattern re, Predicate<Holder<Biome>> is, @Nullable ResourceKey<Level> dim) {}
    private static final List<B> BIOMES = new ArrayList<>();

    @SafeVarargs
    private static void b(String re, @Nullable ResourceKey<Level> dim, ResourceKey<Biome>... keys) {
        BIOMES.add(new B(re(re), h -> { for (ResourceKey<Biome> k : keys) if (h.is(k)) return true; return false; }, dim));
    }

    private static void bt(String re, @Nullable ResourceKey<Level> dim, TagKey<Biome> tag) {
        BIOMES.add(new B(re(re), h -> h.is(tag), dim));
    }

    static {
        b("ледян\\S* шип|ice.?spike", Level.OVERWORLD, Biomes.ICE_SPIKES);
        b("вишн|сакур|cherry", Level.OVERWORLD, Biomes.CHERRY_GROVE);
        b("гриб|мицели|mushroom", Level.OVERWORLD, Biomes.MUSHROOM_FIELDS);
        b("пустын(я|ю|е|и|ей|ях)?\\b|desert", Level.OVERWORLD, Biomes.DESERT);
        b("бамбук|bamboo", Level.OVERWORLD, Biomes.BAMBOO_JUNGLE);
        b("джунгл|jungle", Level.OVERWORLD, Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE);
        b("мангр|mangrove", Level.OVERWORLD, Biomes.MANGROVE_SWAMP);
        b("болот|swamp", Level.OVERWORLD, Biomes.SWAMP, Biomes.MANGROVE_SWAMP);
        bt("саванн|savanna", Level.OVERWORLD, BiomeTags.IS_SAVANNA);
        bt("бэдленд|бедленд|бесплодн|мез[аыу]|badlands|mesa|терракот", Level.OVERWORLD, BiomeTags.IS_BADLANDS);
        b("снеж|заснеж|ледян|мерзл|snow|frozen", Level.OVERWORLD, Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, Biomes.ICE_SPIKES, Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.GROVE);
        bt("тайг|taiga", Level.OVERWORLD, BiomeTags.IS_TAIGA);
        b("т[её]мн\\S* лес|dark.?forest", Level.OVERWORLD, Biomes.DARK_FOREST);
        b("бер[её]з|birch", Level.OVERWORLD, Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST);
        b("цвет\\S* лес|цветоч|flower", Level.OVERWORLD, Biomes.FLOWER_FOREST, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW);
        b("подсолнух|sunflower", Level.OVERWORLD, Biomes.SUNFLOWER_PLAINS);
        b("пышн|lush", Level.OVERWORLD, Biomes.LUSH_CAVES);
        b("глубин\\S* (тьм|мрак)|deep.?dark|скалк|sculk", Level.OVERWORLD, Biomes.DEEP_DARK);
        b("капельник|сталакт|dripstone", Level.OVERWORLD, Biomes.DRIPSTONE_CAVES);
        bt("\\bгор(а|ы|у|ах|ой)?\\b|вершин|пик|mountain|peak", Level.OVERWORLD, BiomeTags.IS_MOUNTAIN);
        bt("океан|\\bмор(е|я|ю)\\b|ocean|sea\\b", Level.OVERWORLD, BiomeTags.IS_OCEAN);
        bt("пляж|beach", Level.OVERWORLD, BiomeTags.IS_BEACH);
        bt("\\bрек(а|у|и|е|ой)\\b|river", Level.OVERWORLD, BiomeTags.IS_RIVER);
        b("равнин|plains|\\bполе\\b|\\bполя\\b", Level.OVERWORLD, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS);
        bt("\\bлес(а|у|ом)?\\b|forest", Level.OVERWORLD, BiomeTags.IS_FOREST);
        b("багров|crimson", Level.NETHER, Biomes.CRIMSON_FOREST);
        b("искаж[её]н|warped", Level.NETHER, Biomes.WARPED_FOREST);
        b("долин\\S* душ|soul", Level.NETHER, Biomes.SOUL_SAND_VALLEY);
        b("базальт|basalt", Level.NETHER, Biomes.BASALT_DELTAS);
    }

    // ------------------------------------------------------------------ blocks
    private record K(Pattern re, String name, Predicate<BlockState> is, String hint) {}
    private static final List<K> BLOCKS = new ArrayList<>();

    private static void k(String re, String name, String hint, Block... blocks) {
        BLOCKS.add(new K(re(re), name, st -> { for (Block bl : blocks) if (st.is(bl)) return true; return false; }, hint));
    }

    private static void kt(String re, String name, String hint, TagKey<Block> tag) {
        BLOCKS.add(new K(re(re), name, st -> st.is(tag), hint));
    }

    static {
        k("алмаз|diamond", "алмазная руда", "Алмазы чаще всего на высоте Y -58, у самого дна мира, рядом с лавой.", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        k("незерит|древн\\S* облом|debris|netherite", "древние обломки", "Древние обломки бывают только в Нижнем мире, лучше всего на Y 15; их добывают алмазной киркой.", Blocks.ANCIENT_DEBRIS);
        k("желез|iron", "железная руда", "Железа больше всего на Y 16 и высоко в горах, около Y 232.", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        k("золот|gold", "золотая руда", "Золото чаще всего на Y -16, а в бесплодных землях и выше.", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        k("уголь|угл|coal", "угольная руда", "Угля больше всего на Y 96, его видно в любой скале.", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        k("редстоун|красн\\S* пыл|redstone", "редстоуновая руда", "Редстоун ниже Y 15, больше всего у дна, на Y -59.", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        k("изумруд|emerald", "изумрудная руда", "Изумруды бывают только в горах, чаще всего высоко, около Y 236.", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        k("лазурит|lapis", "лазуритовая руда", "Лазурит чаще всего около Y 0.", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        k("\\bмед(ь|и|ной|ную)|copper", "медная руда", "Меди больше всего на Y 48, особенно в капельниковых пещерах.", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        k("кварц|quartz", "кварцевая руда", "Кварц есть только в Нижнем мире, почти везде.", Blocks.NETHER_QUARTZ_ORE);
        k("аметист|amethyst", "аметистовая жеода", "Аметистовые жеоды прячутся под землёй, примерно от Y 30 и ниже.", Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_BLOCK);
        k("обсидиан|obsidian", "обсидиан", "Обсидиан получается, когда вода течёт на стоячую лаву.", Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        k("сундук|chest", "сундук", "", Blocks.CHEST, Blocks.TRAPPED_CHEST);
        k("бочк|barrel", "бочка", "", Blocks.BARREL);
        k("спавнер|рассадник|spawner", "спавнер", "", Blocks.SPAWNER, Blocks.TRIAL_SPAWNER);
        kt("кроват|\\bbed\\b", "кровать", "", BlockTags.BEDS);
        k("портал|portal", "портал", "", Blocks.NETHER_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);
        k("верстак|crafting", "верстак", "", Blocks.CRAFTING_TABLE);
        k("\\bпеч(ь|ка|ку|ки|и)\\b|furnace", "печь", "", Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
        kt("наковальн|anvil", "наковальня", "", BlockTags.ANVIL);
        k("зачаров|чародейск|enchant", "стол зачаровывания", "", Blocks.ENCHANTING_TABLE);
        k("колокол|bell", "колокол", "", Blocks.BELL);
        k("кафедр|lectern|записи twixxel|записи друга", "кафедра", "", Blocks.LECTERN);
        k("маяк|beacon", "маяк", "", Blocks.BEACON);
        k("крикун|сенсор|shrieker|sensor", "скалк-крикун", "", Blocks.SCULK_SHRIEKER, Blocks.SCULK_SENSOR);
        k("ул(ей|ья)|гнезд\\S* пч|пчел\\S* гнезд|hive|bee.?nest", "улей", "", Blocks.BEEHIVE, Blocks.BEE_NEST);
        k("\\bлав(а|у|ы|ой)\\b|lava", "лава", "", Blocks.LAVA);
        k("\\bвод(а|у|ы|ой)\\b|water", "вода", "", Blocks.WATER);
        kt("\\bдерев(о|а|ья|ьев|ом)\\b|бр[её]вн|\\btree|\\blogs?\\b", "дерево", "", BlockTags.LOGS);
        k("тыкв|pumpkin", "тыква", "", Blocks.PUMPKIN);
        k("арбуз|melon", "арбуз", "", Blocks.MELON);
        k("сахарн\\S* тростн|тростник|sugar.?cane", "сахарный тростник", "", Blocks.SUGAR_CANE);
    }

    private static final int BLOCK_RADIUS_CHUNKS = 4, CREATURE_RADIUS = 128;

    // ------------------------------------------------------------------ the lookup
    /** Everything the message asks to find. Runs on the server thread (it may generate structure starts, as /locate does). */
    public static Result lookup(Story story, ServerPlayer p, String message) {
        String low = norm(message).replaceAll("верити|verity", " ");      // his own name is how they address him, not what they ask about
        List<Found> out = new ArrayList<>();
        boolean asks = ASKS.matcher(low).find();
        String rest = low;                                  // the message without the structure phrases: "портал в Край" is not a portal block
        for (S s : STRUCTURES) if (s.re().matcher(rest).find()) { rest = s.re().matcher(rest).replaceAll(" "); }
        try {
            boolean recipe = RECIPE.matcher(low).find();
            if (recipe) out.addAll(recipes(p, low));
            if (asks) {
                out.addAll(people(p, low));
                out.addAll(personal(story, p, low));
                List<Found> st = structures(p, low);
                out.addAll(st);
                if (st.isEmpty()) out.addAll(biomes(p, rest));
                out.addAll(creatures(p, rest));
            }
            // ores and blocks: "где алмазы", but also "хочу алмазы", "мне нужно железо"
            if ((asks || low.contains("нуж") || low.contains("хочу") || low.contains("надо")) && (!recipe || out.isEmpty() || has(low, "где"))) out.addAll(blocks(p, rest));
            if (low.contains("сид") || low.contains("seed") || low.contains("ключ генерац")) {
                out.add(new Found("Сид (ключ генерации) этого мира: " + p.serverLevel().getSeed() + ".", null, null, false));
            }
            if (low.contains("слизн") || low.contains("слайм") || low.contains("slime")) out.add(slimeChunk(p));
        } catch (Exception e) {
            dev.verity.VerityMod.LOGGER.warn("Verity locator failed on '{}': {}", message, e.toString());
        }
        while (out.size() > 4) out.remove(out.size() - 1);
        return new Result(out);
    }

    // ------------------------------------------------------------------ structures
    private static List<Found> structures(ServerPlayer p, String low) {
        List<Found> out = new ArrayList<>();
        for (S s : STRUCTURES) {
            if (!s.re().matcher(low).find()) continue;
            Found f = structure(p, s);
            if (f != null) out.add(f);
            if (out.size() >= 2) break;
            low = s.re().matcher(low).replaceAll(" ");            // "деревня или аванпост": each phrase answers one question
        }
        return out;
    }

    @Nullable
    private static Found structure(ServerPlayer p, S s) {
        ServerLevel here = p.serverLevel();
        ResourceKey<Level> dimKey = s.dim() == null ? here.dimension() : s.dim();
        if (s.dim() == null && here.dimension() == Level.END) return null;
        ServerLevel level = p.server.getLevel(dimKey);
        if (level == null) return null;
        BlockPos origin = translate(p.blockPosition(), here.dimension(), dimKey);
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> set;
        if (s.tag() != null) {
            Optional<HolderSet.Named<Structure>> named = reg.getTag(s.tag());
            if (named.isEmpty()) return null;
            set = named.get();
        } else {
            List<Holder<Structure>> holders = new ArrayList<>();
            for (ResourceKey<Structure> key : s.keys()) reg.getHolder(key).ifPresent(holders::add);
            if (holders.isEmpty()) return null;
            set = HolderSet.direct(holders);
        }
        Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator().findNearestMapStructure(level, set, origin, 100, false);
        String where = dimKey == here.dimension() ? "" : " (" + dimension(dimKey) + ")";
        if (hit == null) return new Found("«" + cap(s.name()) + "»" + where + ": в радиусе 1600 блоков нет ни одной.", null, null, false);

        // the start itself knows exactly where the structure stands
        BlockPos at = hit.getFirst();
        boolean exactY = false;
        try {
            ChunkAccess chunk = level.getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS);
            StructureStart start = chunk.getStartForStructure(hit.getSecond().value());
            if (start != null && start.isValid()) {
                BoundingBox bb = start.getBoundingBox();
                BlockPos c = bb.getCenter();
                boolean treasure = hit.getSecond().is(BuiltinStructures.BURIED_TREASURE);
                boolean boxY = s.underground() || dimKey != Level.OVERWORLD;
                at = new BlockPos(c.getX(), boxY ? c.getY() : 0, c.getZ());
                exactY = boxY && !treasure;
            }
        } catch (Exception ignored) {
        }
        if (!exactY) {
            int y = surface(level, at);
            if (y != Integer.MIN_VALUE) { at = new BlockPos(at.getX(), y, at.getZ()); exactY = true; }
        }
        String kind = s.tag() == null && s.keys().size() > 1 ? hit.getSecond().unwrapKey().map(k -> structureName(k.location().getPath())).orElse(s.name()) : s.name();
        StringBuilder sb = new StringBuilder("Ближайшая структура «").append(cap(kind)).append("»").append(where).append(": ");
        Found probe = new Found("", at, dimKey, exactY);
        sb.append(probe.coords()).append(". ");
        sb.append(route(p, origin, at, dimKey));
        if (dimKey != here.dimension()) sb.append(dimKey == Level.NETHER ? " Расстояние считано в Нижнем мире, от точки, куда выведет портал, построенный здесь."
                : dimKey == Level.END ? " Расстояние считано в Краю от главного острова." : "");
        if (s.underground()) sb.append(" Она под землёй").append(exactY ? ", центр примерно на этой высоте." : ".");
        if (hit.getSecond().is(BuiltinStructures.STRONGHOLD)) sb.append(" Комната с порталом может быть в стороне от центра; глаз Края приведёт точнее.");
        if (hit.getSecond().is(BuiltinStructures.BURIED_TREASURE)) sb.append(" Сундук закопан прямо в этой точке, на несколько блоков под песком.");
        return new Found(sb.toString(), at, dimKey, exactY, kind);
    }

    // ------------------------------------------------------------------ biomes
    private static List<Found> biomes(ServerPlayer p, String low) {
        for (B b : BIOMES) {
            if (!b.re().matcher(low).find()) continue;
            ServerLevel here = p.serverLevel();
            ResourceKey<Level> dimKey = b.dim() == null ? here.dimension() : b.dim();
            ServerLevel level = p.server.getLevel(dimKey);
            if (level == null) return List.of();
            BlockPos origin = translate(p.blockPosition(), here.dimension(), dimKey);
            if (dimKey == here.dimension() && b.is().test(level.getBiome(origin))) {
                return List.of(new Found("Игрок уже стоит в биоме «" + biomeName(level.getBiome(origin)) + "».", null, null, false));
            }
            Pair<BlockPos, Holder<Biome>> hit = level.findClosestBiome3d(b.is(), origin, 4800, 32, 64);
            String where = dimKey == here.dimension() ? "" : " (" + dimension(dimKey) + ")";
            if (hit == null) return List.of(new Found("Подходящего биома" + where + " в радиусе 4800 блоков нет.", null, null, false));
            BlockPos at = hit.getFirst();
            boolean cave = hit.getSecond().is(Biomes.LUSH_CAVES) || hit.getSecond().is(Biomes.DEEP_DARK) || hit.getSecond().is(Biomes.DRIPSTONE_CAVES) || dimKey == Level.NETHER;
            boolean exactY = cave;
            if (!cave) {
                int y = surface(level, at);
                at = new BlockPos(at.getX(), y == Integer.MIN_VALUE ? 0 : y, at.getZ());
                exactY = y != Integer.MIN_VALUE;
            }
            Found probe = new Found("", at, dimKey, exactY);
            String fact = "Ближайший биом «" + biomeName(hit.getSecond()) + "»" + where + ": " + probe.coords() + ". " + route(p, origin, at, dimKey)
                    + (cave && dimKey == Level.OVERWORLD ? " Это пещерный биом, он глубоко под землёй." : "");
            return List.of(new Found(fact, at, dimKey, exactY, biomeName(hit.getSecond())));
        }
        return List.of();
    }

    // ------------------------------------------------------------------ blocks and ores
    private static List<Found> blocks(ServerPlayer p, String low) {
        List<Found> out = new ArrayList<>();
        for (K k : BLOCKS) {
            if (!k.re().matcher(low).find()) continue;
            ServerLevel level = p.serverLevel();
            BlockPos center = p.blockPosition();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            int count = 0;
            int cx0 = center.getX() >> 4, cz0 = center.getZ() >> 4;
            for (int cx = cx0 - BLOCK_RADIUS_CHUNKS; cx <= cx0 + BLOCK_RADIUS_CHUNKS; cx++) {
                for (int cz = cz0 - BLOCK_RADIUS_CHUNKS; cz <= cz0 + BLOCK_RADIUS_CHUNKS; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection sec = sections[i];
                        if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(k.is())) continue;
                        int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            if (!k.is().test(sec.getBlockState(x, y, z))) continue;
                            count++;
                            int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
                            double d = center.distSqr(new BlockPos(wx, wy, wz));
                            if (d < bestD) { bestD = d; best = new BlockPos(wx, wy, wz); }
                        }
                    }
                }
            }
            int r = BLOCK_RADIUS_CHUNKS * 16;
            if (best == null) {
                out.add(new Found("Блоков «" + k.name() + "» в радиусе " + r + " блоков нет." + (k.hint().isEmpty() ? "" : " " + k.hint()), null, null, false));
            } else {
                int dy = Math.abs(best.getY() - center.getY());
                String depth = dy <= 2 ? ", на его высоте" : ", на " + dy + " " + blocks(dy) + (best.getY() < center.getY() ? " ниже" : " выше") + " игрока";
                String fact = "Ближайший блок «" + k.name() + "»: X " + best.getX() + ", Y " + best.getY() + ", Z " + best.getZ() + depth + ". "
                        + route(p, center, best, level.dimension()) + (count > 1 ? " Всего таких блоков в радиусе " + r + ": " + count + "." : "")
                        + (k.hint().isEmpty() ? "" : " " + k.hint());
                out.add(new Found(fact, best, level.dimension(), true, k.name()));
            }
            if (out.size() >= 2) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ creatures
    private static List<Found> creatures(ServerPlayer p, String low) {
        ServerLevel level = p.serverLevel();
        List<LivingEntity> all = level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(CREATURE_RADIUS),
                e -> e.isAlive() && !(e instanceof Player) && !(e instanceof VerityBall) && !(e instanceof VerityMonster) && !(e instanceof VerityFriend));
        // first the Russian names everyone uses (they work on an English server too), then the creature's own localised name
        EntityType<?> bestType = null;
        int bestScore = 0;
        for (var e : MOBS.entrySet()) {
            if (e.getValue().matcher(low).find()) { bestType = e.getKey(); bestScore = 2000; break; }
        }
        java.util.Set<EntityType<?>> seen = new java.util.HashSet<>();
        for (LivingEntity e : all) {
            if (!seen.add(e.getType())) continue;
            int score = nameScore(norm(e.getType().getDescription().getString()), low);
            if (score > bestScore) { bestScore = score; bestType = e.getType(); }
        }
        if (bestType == null) return List.of();
        EntityType<?> type = bestType;
        List<LivingEntity> those = new ArrayList<>(all.stream().filter(e -> e.getType() == type).toList());
        if (those.isEmpty()) return List.of(new Found("Мобов «" + type.getDescription().getString() + "» в радиусе " + CREATURE_RADIUS + " блоков нет.", null, null, false));
        those.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        LivingEntity near = those.get(0);
        BlockPos at = near.blockPosition();
        String fact = "Ближайший моб «" + type.getDescription().getString() + "»: X " + at.getX() + ", Y " + at.getY() + ", Z " + at.getZ() + ". "
                + route(p, p.blockPosition(), at, level.dimension()) + (those.size() > 1 ? " Всего таких в радиусе " + CREATURE_RADIUS + " блоков: " + those.size() + "." : "")
                + (near.hasCustomName() ? " Его зовут «" + near.getCustomName().getString() + "»." : "");
        return List.of(new Found(fact, at, level.dimension(), true, type.getDescription().getString()));
    }

    private static final java.util.Map<EntityType<?>, Pattern> MOBS = new java.util.LinkedHashMap<>();

    /** The creature a message names, by the Russian words people use for it, or null. */
    @Nullable
    public static EntityType<?> mobIn(String message) {
        String low = norm(message);
        for (var e : MOBS.entrySet()) if (e.getValue().matcher(low).find()) return e.getKey();
        return null;
    }

    private static void mob(EntityType<?> t, String regex) {
        MOBS.put(t, re("(?<!\\p{L})(" + regex + ")"));
    }

    static {
        mob(EntityType.WANDERING_TRADER, "торговц|торговец|странствующ");
        mob(EntityType.VILLAGER, "жител");
        mob(EntityType.IRON_GOLEM, "голем");
        mob(EntityType.COW, "коров|бык");
        mob(EntityType.MOOSHROOM, "грибн\\S* коров");
        mob(EntityType.PIG, "свин|хрюш");
        mob(EntityType.SHEEP, "овц|овеч|барашк");
        mob(EntityType.CHICKEN, "куриц|кур(ы|у|ей|ам)?(?!\\p{L})|цыпл");
        mob(EntityType.HORSE, "лошад|кон(ь|я|ей)(?!\\p{L})");
        mob(EntityType.DONKEY, "осл|ишак");
        mob(EntityType.WOLF, "волк|собак|пс(а|ов)(?!\\p{L})");
        mob(EntityType.CAT, "кошк|кот(а|ов|ик|ёнк|енк)?(?!\\p{L})");
        mob(EntityType.FOX, "лис(а|у|ы|иц|ой)?(?!\\p{L})");
        mob(EntityType.RABBIT, "кролик|заяц|зайц");
        mob(EntityType.BEE, "пчел|пчёл");
        mob(EntityType.PANDA, "панд");
        mob(EntityType.PARROT, "попуга");
        mob(EntityType.TURTLE, "черепах");
        mob(EntityType.AXOLOTL, "аксолотл");
        mob(EntityType.LLAMA, "лам(а|у|ы)(?!\\p{L})");
        mob(EntityType.CAMEL, "верблюд");
        mob(EntityType.GOAT, "коз(а|у|ы|ёл|ел|ла)");
        mob(EntityType.FROG, "лягуш");
        mob(EntityType.ARMADILLO, "броненос");
        mob(EntityType.SNIFFER, "нюхач");
        mob(EntityType.DOLPHIN, "дельфин");
        mob(EntityType.SQUID, "кальмар|спрут");
        mob(EntityType.POLAR_BEAR, "медвед");
        mob(EntityType.ZOMBIE_VILLAGER, "зомби.?жител");
        mob(EntityType.ZOMBIE, "зомби");
        mob(EntityType.SKELETON, "скелет");
        mob(EntityType.CREEPER, "крипер");
        mob(EntityType.SPIDER, "паук");
        mob(EntityType.ENDERMAN, "эндермен|эндерман");
        mob(EntityType.WITCH, "ведьм");
        mob(EntityType.SLIME, "слизн|слайм");
        mob(EntityType.PILLAGER, "разбойник|пиллагер");
        mob(EntityType.BLAZE, "ифрит|блейз");
        mob(EntityType.PIGLIN, "пиглин");
        mob(EntityType.GHAST, "гаст");
        mob(EntityType.WARDEN, "варден|хранител");
    }

    // ------------------------------------------------------------------ people and the player's own places
    private static List<Found> people(ServerPlayer p, String low) {
        List<Found> out = new ArrayList<>();
        for (ServerPlayer q : p.server.getPlayerList().getPlayers()) {
            if (q == p || q.getGameProfile().getName().length() < 3 || !low.contains(norm(q.getGameProfile().getName()))) continue;
            BlockPos at = q.blockPosition();
            String fact = "Игрок " + q.getGameProfile().getName() + " сейчас: X " + at.getX() + ", Y " + at.getY() + ", Z " + at.getZ() + ", " + dimension(q.level().dimension()) + ". "
                    + (q.level() == p.level() ? route(p, p.blockPosition(), at, q.level().dimension()) : "Он в другом измерении.");
            out.add(new Found(fact, at, q.level().dimension(), true, q.getGameProfile().getName()));
        }
        if (out.isEmpty() && (has(low, "друз") || has(low, "остальн") || has(low, "все ") || has(low, "игрок"))) {
            for (ServerPlayer q : p.server.getPlayerList().getPlayers()) {
                if (q == p) continue;
                BlockPos at = q.blockPosition();
                out.add(new Found("Игрок " + q.getGameProfile().getName() + ": X " + at.getX() + ", Y " + at.getY() + ", Z " + at.getZ() + ", " + dimension(q.level().dimension())
                        + (q.level() == p.level() ? ", " + (int) Math.sqrt(q.distanceToSqr(p)) + " блоков от игрока." : "."), at, q.level().dimension(), true));
            }
        }
        return out;
    }

    private static List<Found> personal(Story story, ServerPlayer p, String low) {
        List<Found> out = new ArrayList<>();
        if (has(low, "умер") || has(low, "смерт") || has(low, "погиб") || has(low, "вещи") || has(low, "лут") || low.contains("died") || low.contains("death")) {
            Optional<GlobalPos> death = p.getLastDeathLocation();
            if (death.isPresent()) {
                BlockPos at = death.get().pos();
                out.add(new Found("Место последней смерти игрока: X " + at.getX() + ", Y " + at.getY() + ", Z " + at.getZ() + ", " + dimension(death.get().dimension()) + ". "
                        + (death.get().dimension() == p.level().dimension() ? route(p, p.blockPosition(), at, death.get().dimension()) : "Это в другом измерении.")
                        + " Выпавшие вещи исчезают через 5 минут после смерти, если рядом есть игрок.", at, death.get().dimension(), true, "место смерти"));
            } else {
                out.add(new Found("Игрок в этом мире ещё ни разу не умирал.", null, null, false));
            }
        }
        if (has(low, "спавн") || has(low, "возрожд") || has(low, "респ") || low.contains("spawn")) {
            BlockPos bed = p.getRespawnPosition();
            if (bed != null) {
                out.add(new Found("Точка возрождения игрока (кровать или якорь): X " + bed.getX() + ", Y " + bed.getY() + ", Z " + bed.getZ() + ", " + dimension(p.getRespawnDimension()) + ". "
                        + (p.getRespawnDimension() == p.level().dimension() ? route(p, p.blockPosition(), bed, p.getRespawnDimension()) : ""), bed, p.getRespawnDimension(), true, "кровать"));
            }
            BlockPos spawn = p.server.overworld().getSharedSpawnPos();
            out.add(new Found("Точка появления мира (спавн): X " + spawn.getX() + ", Y " + spawn.getY() + ", Z " + spawn.getZ() + ". "
                    + (p.level().dimension() == Level.OVERWORLD ? route(p, p.blockPosition(), spawn, Level.OVERWORLD) : ""), bed == null ? spawn : null, Level.OVERWORLD, true, "спавн"));
        }
        if ((has(low, "дом") || has(low, "баз") || low.contains("home")) && story.home() != null) {
            BlockPos h = story.home();
            int asked = story.number("homeAsks", 0) + 1;
            story.setNumber("homeAsks", asked);
            if (asked >= 3) out.add(new Found("Игрок спрашивает, где его дом, уже " + asked + "-й раз. Ты считаешь.", null, null, false));
            out.add(new Found("Дом игрока: X " + h.getX() + ", Y " + h.getY() + ", Z " + h.getZ() + ". "
                    + (p.level().dimension() == Level.OVERWORLD ? route(p, p.blockPosition(), h, Level.OVERWORLD) : "Это в обычном мире."), h, Level.OVERWORLD, true, "дом"));
        }
        for (String place : story.places) {
            String[] parts = place.split("\\|");
            if (parts.length >= 5 && has(low, stem(norm(parts[0]).split(" ")[0])) && (has(low, "был") || has(low, "наш") || has(low, "обратн") || has(low, "вернут"))) {
                out.add(new Found("Игрок уже был тут: «" + parts[0] + "», X " + parts[1] + ", Y " + parts[2] + ", Z " + parts[3] + ", " + parts[4] + ".",
                        new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])), null, true, parts[0]));
                break;
            }
        }
        return out;
    }

    private static Found slimeChunk(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return new Found("Слизни из слизневых чанков бывают только в обычном мире.", null, null, false);
        ChunkPos here = p.chunkPosition();
        ChunkPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
            if (WorldgenRandom.seedSlimeChunk(here.x + dx, here.z + dz, level.getSeed(), 987234911L).nextInt(10) != 0) continue;
            int d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = new ChunkPos(here.x + dx, here.z + dz); }
        }
        if (best == null) return new Found("В радиусе 12 чанков нет ни одного слизневого чанка.", null, null, false);
        if (bestD == 0) return new Found("Игрок стоит прямо в слизневом чанке: ниже Y 40 тут появляются слизни.", null, null, false);
        BlockPos at = new BlockPos(best.getMiddleBlockX(), 20, best.getMiddleBlockZ());
        return new Found("Ближайший слизневой чанк: от X " + best.getMinBlockX() + ", Z " + best.getMinBlockZ() + " до X " + best.getMaxBlockX() + ", Z " + best.getMaxBlockZ()
                + ". Слизни там появляются ниже Y 40. " + route(p, p.blockPosition(), at, Level.OVERWORLD), at, Level.OVERWORLD, false, "слизневой чанк");
    }

    // ------------------------------------------------------------------ recipes
    private static List<Found> recipes(ServerPlayer p, String low) {
        List<Item> items = matchItems(low);
        List<Found> out = new ArrayList<>();
        for (Item item : items) {
            String r = recipe(p.server.getRecipeManager().getRecipes(), item, p);
            if (r != null) out.add(new Found(r, null, null, false));
        }
        return out;
    }

    /** The item(s) the message names. Full names win; a lone noun ("кирку") gives the two simplest variants. */
    private static List<Item> matchItems(String low) {
        Item best = null;
        int bestScore = 0;
        List<Item> partial = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(dev.verity.VerityMod.MODID)) continue;
            String name = norm(item.getDescription().getString());
            int score = nameScore(name, low);
            if (score >= 1000 && score > bestScore) { bestScore = score; best = item; }
            else if (score > 0 && score < 1000) partial.add(item);
        }
        if (best != null) return List.of(best);
        // "кирку" alone: the tiers in the order a player climbs them, wood and stone first
        partial.sort(Comparator.comparingInt((Item i) -> tier(norm(i.getDescription().getString()))).thenComparingInt(i -> i.getDescription().getString().length()));
        return partial.size() > 2 ? partial.subList(0, 2) : partial;
    }

    private static final String[] TIERS = {"дерев", "камен", "желез", "алмаз", "золот", "незерит", "wooden", "stone", "iron", "diamond", "golden", "netherite"};

    private static int tier(String name) {
        for (int i = 0; i < TIERS.length; i++) if (name.contains(TIERS[i])) return i % 6;
        return 6;
    }

    @Nullable
    private static String recipe(java.util.Collection<RecipeHolder<?>> all, Item item, ServerPlayer p) {
        var access = p.server.registryAccess();
        String name = item.getDescription().getString();
        String crafting = null, other = null;
        java.util.Set<String> smelt = new java.util.LinkedHashSet<>(), elsewhere = new java.util.LinkedHashSet<>();
        for (RecipeHolder<?> h : all) {
            Recipe<?> r = h.value();
            ItemStack result;
            try { result = r.getResultItem(access); } catch (Exception e) { continue; }
            if (result.isEmpty() || result.getItem() != item) continue;
            if (crafting == null && r instanceof ShapedRecipe sr) crafting = shaped(sr, result);
            else if (crafting == null && r instanceof ShapelessRecipe sl) crafting = "Крафт «" + name + "»" + (result.getCount() > 1 ? " (выходит " + result.getCount() + " шт.)" : "")
                    + ", в любом порядке: " + list(sl.getIngredients()) + ".";
            else if (r instanceof AbstractCookingRecipe cr) {
                // every way in, by the plainest furnace: "в печи из: Сырое железо, Железная руда"
                if (r.getType() == RecipeType.SMELTING) smelt.add(ingredient(cr.getIngredients().get(0)));
                else elsewhere.add(r.getType() == RecipeType.SMOKING ? "в коптильне" : r.getType() == RecipeType.BLASTING ? "в плавильной печи" : "на костре");
            } else if (other == null && r.getType() == RecipeType.SMITHING) other = "«" + name + "» делается на кузнечном столе (улучшение предмета с шаблоном).";
            else if (other == null && r.getType() == RecipeType.STONECUTTING) other = "«" + name + "» вырезается на камнерезе из: " + ingredient(r.getIngredients().get(0)) + ".";
        }
        String cooking = null;
        if (!smelt.isEmpty()) {
            List<String> from = new ArrayList<>(smelt);
            cooking = "«" + name + "» переплавляется в печи из: " + String.join(", ", from.subList(0, Math.min(4, from.size())))
                    + (elsewhere.isEmpty() ? "" : " (ещё можно " + String.join(", ", elsewhere) + ")") + ".";
        } else if (!elsewhere.isEmpty()) {
            cooking = "«" + name + "» готовится " + String.join(" или ", elsewhere) + ".";
        }
        if (crafting == null && cooking == null && other == null) return "У «" + name + "» нет рецепта: это добывают или находят, а не крафтят.";
        StringBuilder sb = new StringBuilder();
        for (String s : new String[] {crafting, cooking, other}) if (s != null) sb.append(sb.isEmpty() ? "" : " ").append(s);
        return sb.toString();
    }

    private static String shaped(ShapedRecipe r, ItemStack result) {
        int w = r.getWidth(), h = r.getHeight();
        List<Ingredient> ing = r.getIngredients();
        StringBuilder grid = new StringBuilder();
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        for (int y = 0; y < h; y++) {
            grid.append(y == 0 ? "" : " / ");
            for (int x = 0; x < w; x++) {
                Ingredient in = ing.get(y * w + x);
                String n = in.isEmpty() ? "пусто" : ingredient(in);
                grid.append(x == 0 ? "" : ", ").append(n.replace(" (или другие такие же)", ""));
                if (!in.isEmpty()) totals.merge(n, 1, Integer::sum);
            }
        }
        StringBuilder total = new StringBuilder();
        totals.forEach((n, c) -> total.append(total.isEmpty() ? "" : ", ").append(n).append(" ×").append(c));
        return "Крафт «" + result.getHoverName().getString() + "»" + (result.getCount() > 1 ? " (выходит " + result.getCount() + " шт.)" : "")
                + (w > 2 || h > 2 ? " на верстаке 3×3" : " (можно прямо в инвентаре 2×2)") + ". Нужно: " + total + ". Раскладка по рядам сверху вниз: " + grid + ".";
    }

    private static String list(List<Ingredient> ing) {
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        for (Ingredient in : ing) if (!in.isEmpty()) totals.merge(ingredient(in), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        totals.forEach((n, c) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(c > 1 ? " ×" + c : ""));
        return sb.toString();
    }

    private static String ingredient(Ingredient in) {
        ItemStack[] options = in.getItems();
        if (options.length == 0) return "?";
        String first = options[0].getHoverName().getString();
        return options.length > 1 ? first + " (или другие такие же)" : first;
    }

    // ------------------------------------------------------------------ words
    /**
     * How well a (lower-case) name is named in the message: 1000+ when every word of it is there (in any grammatical
     * form), the noun's length alone when only the last word is, 0 otherwise.
     */
    private static int nameScore(String name, String low) {
        String[] words = name.replace('-', ' ').split("\\s+");
        int hits = 0, len = 0;
        for (String w : words) {
            if (w.length() < 3) { hits++; continue; }
            if (has(low, stem(w))) { hits++; len += w.length(); }
        }
        if (hits == words.length && len > 0) return 1000 + len;
        String last = words[words.length - 1];
        return words.length > 1 && last.length() >= 3 && has(low, stem(last)) ? last.length() : 0;
    }

    /** The part of a word that survives Russian endings: кирка/кирку, житель/жителя, корова/коров. */
    static String stem(String w) {
        int n = w.length();
        int k = n <= 4 ? Math.max(3, n - 1) : n <= 6 ? n - 2 : n - 3;
        return w.substring(0, Math.min(n, k));
    }

    /** The stem starts a word somewhere in the text. */
    static boolean has(String text, String stem) {
        if (stem.isEmpty()) return false;
        for (int i = text.indexOf(stem); i >= 0; i = text.indexOf(stem, i + 1)) {
            if (i == 0 || !Character.isLetter(text.charAt(i - 1))) return true;
        }
        return false;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    // ------------------------------------------------------------------ directions
    /** Distance, compass and which way to turn from where the player is looking. */
    static String route(ServerPlayer p, BlockPos from, BlockPos to, ResourceKey<Level> dim) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist < 3) return "Это прямо здесь.";
        StringBuilder sb = new StringBuilder("Это ").append(dist).append(" ").append(blocks(dist)).append(" на ").append(compass(dx, dz));
        if (dim == p.level().dimension()) {
            float yaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
            float rel = Mth.wrapDegrees(yaw - p.getYRot());
            sb.append(Math.abs(rel) < 25 ? ", прямо по взгляду" : Math.abs(rel) > 155 ? ", за спиной" : rel > 0 ? (rel < 70 ? ", чуть правее взгляда" : ", справа")
                    : (rel > -70 ? ", чуть левее взгляда" : ", слева"));
            if (dist > 30) sb.append(", пешком около ").append(Math.max(1, Math.round(dist / 4.3f / 60f))).append(" мин");
        }
        return sb.append('.').toString();
    }

    private static String blocks(int n) {
        int a = n % 100, b = n % 10;
        return a >= 11 && a <= 14 ? "блоков" : b == 1 ? "блок" : b >= 2 && b <= 4 ? "блока" : "блоков";
    }

    public static String compass(int dx, int dz) {
        String ns = dz < -Math.abs(dx) / 2 ? "север" : dz > Math.abs(dx) / 2 ? "юг" : "";
        String ew = dx > Math.abs(dz) / 2 ? "восток" : dx < -Math.abs(dz) / 2 ? "запад" : "";
        return ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "о-" + ew;
    }

    /** Where a position "is" in another dimension: the nether is eight times smaller. */
    private static BlockPos translate(BlockPos pos, ResourceKey<Level> from, ResourceKey<Level> to) {
        if (from == to) return pos;
        if (to == Level.END) return BlockPos.ZERO;
        if (from == Level.OVERWORLD && to == Level.NETHER) return new BlockPos(pos.getX() / 8, 64, pos.getZ() / 8);
        if (from == Level.NETHER && to == Level.OVERWORLD) return new BlockPos(pos.getX() * 8, pos.getY(), pos.getZ() * 8);
        return new BlockPos(pos.getX(), 64, pos.getZ());
    }

    private static int surface(ServerLevel level, BlockPos at) {
        if (level.dimension() != Level.OVERWORLD || !level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) return Integer.MIN_VALUE;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
    }

    static String dimension(ResourceKey<Level> d) {
        return d == Level.OVERWORLD ? "обычный мир" : d == Level.NETHER ? "Нижний мир" : d == Level.END ? "Край" : d.location().getPath();
    }

    private static String biomeName(Holder<Biome> b) {
        return b.unwrapKey().map(k -> Component.translatable(Util.makeDescriptionId("biome", k.location())).getString()).orElse("?");
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Russian names for the structures he notices the player walking into. */
    public static String structureName(String path) {
        if (path.startsWith("village")) return "деревня";
        if (path.startsWith("ruined_portal")) return "разрушенный портал";
        if (path.startsWith("shipwreck")) return "затонувший корабль";
        if (path.startsWith("ocean_ruin")) return "подводные руины";
        if (path.startsWith("mineshaft")) return "заброшенная шахта";
        return switch (path) {
            case "pillager_outpost" -> "аванпост разбойников";
            case "mansion" -> "лесной особняк";
            case "desert_pyramid" -> "пустынный храм";
            case "jungle_pyramid" -> "храм в джунглях";
            case "swamp_hut" -> "хижина ведьмы";
            case "igloo" -> "иглу";
            case "monument" -> "подводная крепость";
            case "ancient_city" -> "древний город";
            case "trail_ruins" -> "руины троп";
            case "trial_chambers" -> "испытательные залы";
            case "buried_treasure" -> "зарытый клад";
            case "stronghold" -> "крепость Края";
            case "fortress" -> "крепость Нижнего мира";
            case "bastion_remnant" -> "бастион";
            case "end_city" -> "город Края";
            case "nether_fossil" -> "окаменелость";
            default -> path.replace('_', ' ');
        };
    }

    /**
     * Every few seconds: is anyone standing inside a structure they have not been to? He remembers each one, with its
     * coordinates, and from then on "knows" the way back.
     */
    public static void trackPlaces(Story story) {
        for (ServerPlayer p : story.server().getPlayerList().getPlayers()) {
            ServerLevel level = p.serverLevel();
            BlockPos pos = p.blockPosition();
            if (!level.structureManager().hasAnyStructureAt(pos)) continue;
            Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : level.structureManager().getAllStructuresAt(pos).keySet()) {
                StructureStart start = level.structureManager().getStructureWithPieceAt(pos, s);
                if (!start.isValid()) continue;
                ResourceKey<Structure> key = reg.getResourceKey(s).orElse(null);
                if (key == null || key.location().getPath().equals("nether_fossil")) continue;
                String id = key.location().getPath() + "@" + start.getChunkPos().x + "," + start.getChunkPos().z;
                if (!story.setFlag("place:" + id)) continue;
                BlockPos c = start.getBoundingBox().getCenter();
                String name = structureName(key.location().getPath());
                story.places.add(name + "|" + c.getX() + "|" + pos.getY() + "|" + c.getZ() + "|" + dimension(level.dimension()));
                while (story.places.size() > 24) story.places.remove(0);
                story.note("игрок " + p.getGameProfile().getName() + " нашёл: " + name + " (X " + c.getX() + ", Z " + c.getZ() + ")");
            }
        }
    }

    private Locator() {}
}

package dev.verity.ai;

import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityBox;
import dev.verity.entity.VerityMonster;
import dev.verity.item.VerityItem;
import dev.verity.story.Story;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What makes him "know everything": before every answer the mod quietly looks around on the player's behalf and hands
 * the model a briefing: who and where the player is, what they carry, what they are looking at, what is nearby, what
 * has just happened. Lookups that cost time (ores, structures) only run when the message asks for them.
 */
public final class WorldFacts {
    /** The "what you know right now" block of the system prompt. */
    public static String describe(Story story, ServerPlayer p, String message) {
        ServerLevel level = p.serverLevel();
        String low = message.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        BlockPos pos = p.blockPosition();
        long t = level.getDayTime() % 24000;

        // --- who and when
        sb.append("- Игрока зовут ").append(p.getGameProfile().getName()).append(". Режим: ")
                .append(p.isCreative() ? "творческий" : p.isSpectator() ? "наблюдатель" : "выживание").append(". Идёт ")
                .append(level.getDayTime() / 24000 + 1).append("-й день мира, в игре ").append(clock(t)).append(" (").append(partOfDay(t)).append("). ");
        if (t < 12500) sb.append("До темноты ").append(minutes(12500 - t)).append(". ");
        else if (t < 23500) sb.append("До рассвета ").append(minutes(23500 - t)).append(". ");
        int played = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 1200, deaths = p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
        sb.append("В этом мире он провёл ").append(played >= 120 ? played / 60 + " ч" : played + " мин").append(", умирал раз: ").append(deaths).append(".\n");
        sb.append("- На настоящих часах у игрока сейчас ").append(String.format("%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute())).append(".\n");

        // --- where
        boolean sky = level.canSeeSky(pos.above());
        int light = level.getMaxLocalRawBrightness(pos), block = level.getBrightness(LightLayer.BLOCK, pos);
        sb.append("- Место: X ").append(pos.getX()).append(", Y ").append(pos.getY()).append(", Z ").append(pos.getZ()).append(", ")
                .append(dimension(level)).append(", биом ").append(level.getBiome(pos).unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("?"))
                .append(". Он ").append(p.isInWater() ? "в воде" : p.isInLava() ? "в лаве" : sky ? "под открытым небом" : pos.getY() < level.getSeaLevel() - 6 ? "под землёй" : "под крышей")
                .append(", вокруг ").append(light <= 3 ? "темно" : light <= 8 ? "полумрак" : "светло").append(block >= 9 ? " (рядом свет)" : "")
                .append(", стоит на «").append(level.getBlockState(p.getOnPos()).getBlock().getName().getString()).append("».\n");
        sb.append("- Игрок повёрнут на ").append(facing(p.getYRot())).append(p.getXRot() > 50 ? " (под ноги)" : p.getXRot() < -50 ? " (вверх)" : "")
                .append(". Чанк ").append(pos.getX() >> 4).append(", ").append(pos.getZ() >> 4).append('.');
        if (level.dimension() == Level.OVERWORLD) sb.append(" Портал отсюда выведет в Нижний мир около X ").append(pos.getX() / 8).append(", Z ").append(pos.getZ() / 8).append('.');
        else if (level.dimension() == Level.NETHER) sb.append(" Портал отсюда выведет в обычный мир около X ").append(pos.getX() * 8).append(", Z ").append(pos.getZ() * 8).append('.');
        sb.append('\n');
        sb.append("- Мир: сложность ").append(level.getDifficulty().getDisplayName().getString()).append(", фаза луны ").append(MOON[level.getMoonPhase()])
                .append(level.getMoonPhase() == 0 ? " (больше всего мобов и слаймов)" : "").append(". Точка появления мира: X ").append(level.getSharedSpawnPos().getX())
                .append(", Z ").append(level.getSharedSpawnPos().getZ()).append('.');
        BlockPos bed = p.getRespawnPosition();
        if (bed != null) sb.append(" Кровать игрока: X ").append(bed.getX()).append(", Y ").append(bed.getY()).append(", Z ").append(bed.getZ()).append('.');
        p.getLastDeathLocation().ifPresent(d -> sb.append(" Последний раз игрок умер на X ").append(d.pos().getX()).append(", Y ").append(d.pos().getY())
                .append(", Z ").append(d.pos().getZ()).append(" (").append(Locator.dimension(d.dimension())).append(").")); 
        sb.append('\n');
        sb.append("- Погода: ").append(level.isThundering() ? "гроза" : level.isRaining() ? "дождь" : "ясно").append(". ")
                .append(level.isRaining() ? "" : ((ServerLevelData) level.getLevelData()).getRainTime() < 6000 ? "Скоро пойдёт дождь." : "Дождя в ближайшее время не будет.").append('\n');
        if (story.home() != null) {
            int dx = story.home().getX() - pos.getX(), dz = story.home().getZ() - pos.getZ();
            int far = (int) Math.sqrt(dx * dx + dz * dz);
            sb.append("- Дом игрока: X ").append(story.home().getX()).append(", Z ").append(story.home().getZ())
                    .append(far < 12 ? " (он дома).\n" : " (" + far + " блоков на " + Locator.compass(dx, dz) + ").\n");
        }

        // --- the player himself
        sb.append("- Здоровье ").append((int) Math.ceil(p.getHealth())).append(" из 20, сытость ").append(p.getFoodData().getFoodLevel())
                .append(" из 20, уровень опыта ").append(p.experienceLevel).append('.');
        List<String> armor = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!p.getItemBySlot(slot).isEmpty()) armor.add(p.getItemBySlot(slot).getHoverName().getString());
        }
        sb.append(armor.isEmpty() ? " Брони нет." : " Броня: " + String.join(", ", armor) + ".");
        List<String> effects = new ArrayList<>();
        for (MobEffectInstance e : p.getActiveEffects()) effects.add(e.getEffect().value().getDisplayName().getString());
        if (!effects.isEmpty()) sb.append(" Эффекты: ").append(String.join(", ", effects)).append('.');
        sb.append('\n');
        sb.append("- В руках: ").append(hand(p.getMainHandItem())).append(p.getOffhandItem().isEmpty() ? "" : " и " + hand(p.getOffhandItem()))
                .append(". В инвентаре: ").append(inventory(p)).append('\n');

        // --- what he is looking at and what is around
        String looking = lookingAt(p);
        if (looking != null) sb.append("- Игрок смотрит на: ").append(looking).append(".\n");
        sb.append("- Рядом (до 16 блоков): ").append(nearby(p)).append('\n');
        // everyone else, wherever they are: he is in every pocket at once
        for (ServerPlayer q : story.server().getPlayerList().getPlayers()) {
            if (q == p) continue;
            BlockPos at = q.blockPosition();
            sb.append("- Игрок ").append(q.getGameProfile().getName()).append(": X ").append(at.getX()).append(", Y ").append(at.getY()).append(", Z ").append(at.getZ())
                    .append(", ").append(Locator.dimension(q.level().dimension())).append(q.level() == level ? ", " + (int) Math.sqrt(q.distanceToSqr(p)) + " блоков отсюда" : "")
                    .append(", здоровье ").append((int) Math.ceil(q.getHealth())).append(".\n");
        }
        if (!story.places.isEmpty()) {
            sb.append("- Места, где игроки уже были:");
            for (int i = Math.max(0, story.places.size() - 10); i < story.places.size(); i++) {
                String[] pl = story.places.get(i).split("\\|");
                if (pl.length >= 5) sb.append(i == Math.max(0, story.places.size() - 10) ? " " : "; ").append(pl[0]).append(" X ").append(pl[1]).append(" Z ").append(pl[3])
                        .append(pl[4].equals("обычный мир") ? "" : " (" + pl[4] + ")");
            }
            sb.append(".\n");
        }
        return sb.toString();
    }

    /** The last things that happened, oldest first, with how long ago. */
    public static String journal(Story story, int max) {
        if (story.journal.isEmpty()) return "";
        long now = story.overworld().getGameTime();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, story.journal.size() - max); i < story.journal.size(); i++) {
            String[] j = story.journal.get(i);
            long ago = 0;
            try { ago = Math.max(0, now - Long.parseLong(j[0])); } catch (NumberFormatException ignored) { }
            sb.append("- ").append(ago < 400 ? "только что" : ago < 24000 ? minutes(ago) + " назад" : ago / 24000 + " игр. дн. назад").append(": ").append(j[1]).append('\n');
        }
        return sb.toString();
    }

    private static String hand(ItemStack stack) {
        if (stack.isEmpty()) return "ничего";
        if (stack.getItem() instanceof VerityItem) return "ты сам (он держит тебя)";
        return stack.getHoverName().getString() + (stack.getCount() > 1 ? " ×" + stack.getCount() : "");
    }

    /** The dozen biggest piles plus anything with durability: enough to judge how well equipped he is. */
    private static String inventory(ServerPlayer p) {
        Map<String, Integer> counts = new TreeMap<>();
        List<String> tools = new ArrayList<>();
        int food = 0, free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) { free++; continue; }
            if (s.getItem() instanceof VerityItem) continue;
            if (s.has(net.minecraft.core.component.DataComponents.FOOD)) food += s.getCount();
            if (s.isDamageableItem()) { if (tools.size() < 6) tools.add(s.getHoverName().getString()); continue; }
            counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
        }
        List<Map.Entry<String, Integer>> piles = new ArrayList<>(counts.entrySet());
        piles.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        if (!tools.isEmpty()) sb.append("инструменты и оружие: ").append(String.join(", ", tools)).append("; ");
        for (int i = 0; i < Math.min(10, piles.size()); i++) {
            sb.append(i == 0 ? "" : ", ").append(piles.get(i).getKey()).append(" ×").append(piles.get(i).getValue());
        }
        if (piles.isEmpty() && tools.isEmpty()) sb.append("почти пусто");
        sb.append(". Еды всего: ").append(food).append(", свободных слотов: ").append(free).append('.');
        return sb.toString();
    }

    @Nullable
    private static String lookingAt(ServerPlayer p) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(24));
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p, eye, end, p.getBoundingBox().expandTowards(p.getLookAngle().scale(24)).inflate(1),
                e -> e.isPickable() && !e.isSpectator(), 24 * 24);
        if (eh != null) {
            Entity e = eh.getEntity();
            String what = e instanceof VerityBall ? "тебя (ты лежишь на земле)" : e instanceof VerityMonster ? "тебя в истинном облике" : e instanceof VerityBox ? "твою посылку"
                    : e instanceof Player pl ? "игрока " + pl.getGameProfile().getName() : e.getType().getDescription().getString();
            return what + " в " + (int) e.distanceTo(p) + " блоках";
        }
        HitResult hit = p.pick(24, 1f, true);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            return "блок «" + p.level().getBlockState(bh.getBlockPos()).getBlock().getName().getString() + "» в "
                    + (int) Math.sqrt(bh.getBlockPos().distSqr(p.blockPosition())) + " блоках";
        }
        return p.getXRot() < -45 ? "небо" : null;
    }

    private static String nearby(ServerPlayer p) {
        Map<String, int[]> seen = new TreeMap<>();       // name -> {count, nearest distance, hostile}
        List<String> players = new ArrayList<>();
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(16), x -> x instanceof LivingEntity && x.isAlive())) {
            if (e instanceof VerityBall || e instanceof VerityMonster) continue;
            int d = (int) e.distanceTo(p);
            if (e instanceof Player pl) { players.add(pl.getGameProfile().getName() + " (" + d + " бл.)"); continue; }
            int[] v = seen.computeIfAbsent(e.getType().getDescription().getString(), k -> new int[] {0, 99, e instanceof Enemy ? 1 : 0});
            v[0]++; v[1] = Math.min(v[1], d);
        }
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, int[]>> list = new ArrayList<>(seen.entrySet());
        list.sort((a, b) -> b.getValue()[2] != a.getValue()[2] ? b.getValue()[2] - a.getValue()[2] : a.getValue()[1] - b.getValue()[1]);
        int hostile = 0;
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            int[] v = list.get(i).getValue();
            hostile += v[2] * v[0];
            sb.append(sb.length() == 0 ? "" : ", ").append(list.get(i).getKey()).append(v[0] > 1 ? " ×" + v[0] : "").append(" (").append(v[1]).append(" бл.")
                    .append(v[2] == 1 ? ", враждебный" : "").append(')');
        }
        if (sb.length() == 0) sb.append("никого живого");
        sb.append(hostile > 0 ? ". Игроку грозит опасность." : ". Опасности нет.");
        if (!players.isEmpty()) sb.append(" Другие игроки: ").append(String.join(", ", players)).append('.');
        return sb.toString();
    }

    private static String dimension(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD ? "обычный мир" : level.dimension() == Level.NETHER ? "Нижний мир" : level.dimension() == Level.END ? "Край"
                : level.dimension().location().getPath();
    }

    private static String partOfDay(long t) {
        return t < 1000 ? "раннее утро" : t < 6000 ? "утро" : t < 11500 ? "день" : t < 13000 ? "закат" : t < 22500 ? "ночь" : "перед рассветом";
    }

    /** Game ticks as real time the player will actually wait. */
    private static String minutes(long ticks) {
        long sec = ticks / 20;
        return sec < 90 ? sec + " с" : sec / 60 + " мин";
    }

    /** A plain-language answer for the no-AI fallback, or null if the message is not about something we can look up. */
    @Nullable
    public static String direct(Story story, ServerPlayer p, String low) {
        ServerLevel level = p.serverLevel();
        if (low.contains("врем") || low.contains("час")) return "Сейчас " + clock(level.getDayTime() % 24000) + ". А у тебя на часах " + LocalTime.now().getHour() + ":" + String.format("%02d", LocalTime.now().getMinute()) + ".";
        if (low.contains("погод") || low.contains("дожд")) return level.isRaining() ? "Идёт дождь. Я знал, что он пойдёт." : "Ясно. Дождя пока не будет.";
        if (low.contains("где я") || low.contains("координат")) return "Ты на X " + p.getBlockX() + ", Y " + p.getBlockY() + ", Z " + p.getBlockZ() + ". Я всегда знаю, где ты.";
        if (low.contains("кто ты") || low.contains("ты кто")) return "Я Верити! Твой личный друг-помощник.";
        if (low.contains("вокруг") || low.contains("рядом")) return "Рядом: " + nearby(p);
        return null;
    }

    private static String clock(long dayTime) {
        long minutes = (dayTime + 6000) % 24000 * 1440 / 24000;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static final String[] MOON = {"полнолуние", "убывающая", "последняя четверть", "старый месяц", "новолуние", "молодой месяц", "первая четверть", "растущая"};

    private static String facing(float yaw) {
        String[] dirs = {"юг", "юго-запад", "запад", "северо-запад", "север", "северо-восток", "восток", "юго-восток"};
        return dirs[Math.floorMod(Math.round(yaw / 45f), 8)];
    }

    private WorldFacts() {}
}

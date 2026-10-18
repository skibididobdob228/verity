package dev.verity.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.verity.entity.Emotion;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityMonster;
import dev.verity.registry.ModEntities;
import dev.verity.story.Story;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** /verity ... : debug and showcase controls (operators only). */
public final class VerityCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("verity").requires(s -> s.hasPermission(2))
                .then(Commands.literal("summon").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> b.suggest("ball").suggest("monster").suggest("box").buildFuture())
                        .executes(VerityCommand::summon)))
                .then(Commands.literal("emotion").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> { for (Emotion e : Emotion.values()) b.suggest(e.name().toLowerCase()); return b.buildFuture(); })
                        .executes(c -> { Story.get(c.getSource().getLevel()).setEmotion(Emotion.byName(StringArgumentType.getString(c, "name"), Emotion.HAPPY)); return 1; })))
                .then(Commands.literal("corruption").then(Commands.argument("value", FloatArgumentType.floatArg(0, 1))
                        .executes(c -> { Story.get(c.getSource().getLevel()).setCorruption(FloatArgumentType.getFloat(c, "value")); return 1; })))
                .then(Commands.literal("context").executes(c -> {
                    // exactly what the AI is told before it answers
                    Story st = Story.get(c.getSource().getLevel());
                    var player = c.getSource().getPlayerOrException();
                    String prompt = dev.verity.ai.Persona.systemPrompt(st, player, "", "");
                    int from = prompt.indexOf("Что ты видишь и знаешь прямо сейчас");
                    for (String line : prompt.substring(Math.max(from, 0), prompt.indexOf("Ответь ОДНИМ JSON")).strip().split("\n")) {
                        if (!line.isBlank()) c.getSource().sendSuccess(() -> Component.literal(line).withStyle(net.minecraft.ChatFormatting.GRAY), false);
                    }
                    c.getSource().sendSuccess(() -> Component.literal("Реплик в памяти дословно: " + st.memory.size() + ", весь промпт: " + prompt.length()
                            + " символов, последний запрос: " + st.brain().lastPromptTokens + " токенов").withStyle(net.minecraft.ChatFormatting.YELLOW), false);
                    return 1;
                }))
                .then(Commands.literal("find").then(Commands.argument("question", StringArgumentType.greedyString()).executes(c -> {
                    // what the locator finds for a question, without asking the model
                    Story st = Story.get(c.getSource().getLevel());
                    var player = c.getSource().getPlayerOrException();
                    long t0 = System.nanoTime();
                    dev.verity.ai.Locator.Result r = dev.verity.ai.Locator.lookup(st, player, StringArgumentType.getString(c, "question"));
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    for (dev.verity.ai.Locator.Found f : r.found()) c.getSource().sendSuccess(() -> Component.literal(f.fact()).withStyle(net.minecraft.ChatFormatting.GRAY), false);
                    c.getSource().sendSuccess(() -> Component.literal(r.isEmpty() ? "ничего не найдено (" + ms + " мс)" : "найдено: " + r.found().size() + " (" + ms + " мс)")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
                    if (r.target() != null) st.guide(player, r.target());
                    return 1;
                })))
                .then(Commands.literal("summarize").executes(c -> { Story st = Story.get(c.getSource().getLevel()); st.brain().summarize(st); return 1; }))
                .then(Commands.literal("pickup").executes(c -> {
                    Story st = Story.get(c.getSource().getLevel());
                    if (st.ball() != null) st.onBallPoked(st.ball(), c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("give").executes(c -> { Story.get(c.getSource().getLevel()).giveTo(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("drop").executes(c -> { Story.get(c.getSource().getLevel()).materialize(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("start").executes(c -> {
                    Story.get(c.getSource().getLevel()).deliverBox(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("labtp").then(Commands.argument("where", StringArgumentType.word())
                        .suggests((c, b) -> b.suggest("corridor").suggest("names").suggest("machine").suggest("end").suggest("top").buildFuture())
                        .executes(c -> {
                            dev.verity.story.Lab.debugTeleport(Story.get(c.getSource().getLevel()), c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "where"));
                            return 1;
                        })))
                .then(Commands.literal("use").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> b.suggest("lever").suggest("choice").suggest("stay").suggest("seal").buildFuture())
                        .executes(c -> {
                            dev.verity.story.Lab.debugUse(Story.get(c.getSource().getLevel()), c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "what"));
                            return 1;
                        })))
                .then(Commands.literal("open").executes(c -> {
                    int n = 0;
                    for (Entity e : c.getSource().getLevel().getAllEntities()) {
                        if (e instanceof dev.verity.entity.VerityBox box) { box.open(c.getSource().getPlayerOrException()); n++; }
                    }
                    return n;
                }))
                .then(Commands.literal("reset").executes(c -> { Story.get(c.getSource().getLevel()).reset(); return 1; }))
                .then(Commands.literal("stage").executes(c -> {
                    Story s = Story.get(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.literal("Verity: stage " + s.stage() + ", days left " + s.daysLeft() + ", anger " + s.anger()), false);
                    return 1;
                }).then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> { for (Story.Stage st : Story.Stage.values()) b.suggest(st.name().toLowerCase()); return b.buildFuture(); })
                        .executes(c -> {
                            Story.Stage st = Story.Stage.valueOf(StringArgumentType.getString(c, "name").toUpperCase(java.util.Locale.ROOT));
                            Story.get(c.getSource().getLevel()).jumpTo(st, c.getSource().getPlayerOrException());
                            return 1;
                        })))
                .then(Commands.literal("say").then(Commands.argument("line", StringArgumentType.greedyString()).executes(c -> {
                    Story s = Story.get(c.getSource().getLevel());
                    String line = StringArgumentType.getString(c, "line");
                    if (dev.verity.story.Lines.get(line) != null) s.say(s.speaker(), line);
                    else s.sayText(s.speaker(), line, Emotion.HAPPY, s.monster() != null ? "monster" : "ball");
                    return 1;
                })))
                .then(Commands.literal("fx").then(Commands.argument("kind", IntegerArgumentType.integer(0, 5)).executes(c -> {
                    Story.get(c.getSource().getLevel()).fx(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "kind"), 1f, 40);
                    return 1;
                })))
                .then(Commands.literal("city").executes(c -> {
                    boolean ok = Story.get(c.getSource().getLevel()).cityScare(c.getSource().getPlayerOrException());
                    if (!ok) c.getSource().sendFailure(Component.literal("Город не разрешён (или часовой пояс без города)"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("apps").executes(c -> {
                    boolean ok = Story.get(c.getSource().getLevel()).appScare(c.getSource().getPlayerOrException());
                    if (!ok) c.getSource().sendFailure(Component.literal("Не разрешено или знакомых программ не открыто"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("name").executes(c -> {
                    boolean ok = Story.get(c.getSource().getLevel()).nameScare(c.getSource().getPlayerOrException());
                    if (!ok) c.getSource().sendFailure(Component.literal("Он не знает имени: оно вводится на экране при входе в мир"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("note").executes(c -> {
                    boolean ok = Story.get(c.getSource().getLevel()).noteScare(c.getSource().getPlayerOrException());
                    if (!ok) c.getSource().sendFailure(Component.literal("Не разрешено на экране при входе в мир"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("haunt").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> { for (String h : HAUNTS) b.suggest(h); return b.buildFuture(); })
                        .executes(c -> {
                            boolean ok = Story.get(c.getSource().getLevel()).debugHaunt(StringArgumentType.getString(c, "what"), c.getSource().getPlayerOrException());
                            if (!ok) c.getSource().sendFailure(Component.literal("Здесь это не получилось (нет места, двери, животных или недавних слов игрока)"));
                            return ok ? 1 : 0;
                        })))
                .then(Commands.literal("diary").executes(c -> { Story.get(c.getSource().getLevel()).leaveDiary(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("strike").executes(c -> { Story.get(c.getSource().getLevel()).debugStrike(); return 1; }))
                .then(Commands.literal("wall").executes(c -> wall(c, -1)).then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> { for (String k : dev.verity.net.FxPayload.WALL_KINDS) b.suggest(k); return b.buildFuture(); })
                        .executes(c -> wall(c, java.util.List.of(dev.verity.net.FxPayload.WALL_KINDS).indexOf(StringArgumentType.getString(c, "what"))))))
                .then(Commands.literal("sanity").executes(c -> {
                    Story s = Story.get(c.getSource().getLevel());
                    StringBuilder sb = new StringBuilder("Рассудок (уровень из 6, чем выше — тем хуже):");
                    for (net.minecraft.server.level.ServerPlayer q : c.getSource().getServer().getPlayerList().getPlayers()) {
                        sb.append("\n  ").append(q.getGameProfile().getName()).append(q.getUUID().equals(s.ownerId()) ? " (его человек)" : "")
                                .append(": ").append(s.sanityLevel(q)).append(" (").append(Math.round(s.sanity(q))).append(" из 100)");
                    }
                    c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                }).then(Commands.argument("level", IntegerArgumentType.integer(0, 6)).executes(c -> {
                    Story.get(c.getSource().getLevel()).setSanityLevel(IntegerArgumentType.getInteger(c, "level"));
                    return 1;
                }).then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                    Story.get(c.getSource().getLevel()).setSanityLevel(net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"),
                            IntegerArgumentType.getInteger(c, "level"));
                    return 1;
                }))).then(Commands.literal("see").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> { for (String k : dev.verity.net.FxPayload.SEE_KINDS) b.suggest(k); return b.buildFuture(); })
                        .executes(c -> {
                            int which = java.util.List.of(dev.verity.net.FxPayload.SEE_KINDS).indexOf(StringArgumentType.getString(c, "what"));
                            Story.get(c.getSource().getLevel()).fx(c.getSource().getPlayerOrException(), dev.verity.net.FxPayload.SEE, 1f, which);
                            return which >= 0 ? 1 : 0;
                        }))))
                .then(Commands.literal("scare").executes(c -> Story.get(c.getSource().getLevel()).scareEveryone() ? 1 : 0))
                .then(Commands.literal("friend").executes(c -> {
                    Story s = Story.get(c.getSource().getLevel());
                    if (s.friend() == null) s.spawnFriend(c.getSource().getPlayerOrException()); else s.takeFriend(s.friend());
                    return 1;
                }))
                .then(Commands.literal("owner").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                    Story.get(c.getSource().getLevel()).transferTo(net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"), false);
                    return 1;
                })))
                .then(Commands.literal("calm").executes(c -> { Story.get(c.getSource().getLevel()).calm(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("anchor").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3(false))
                        .executes(c -> forBalls(c, ball -> {
                            ball.setAnchor(net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "pos"));
                            ball.setMode(VerityBall.MODE_STAY);
                        }))))
                .then(Commands.literal("ground").executes(c -> forBalls(c, ball -> { ball.setAnchor(null); ball.setMode(VerityBall.MODE_GROUND); })))
                .then(Commands.literal("monsterstate").then(Commands.argument("state", IntegerArgumentType.integer(0, 5))
                        .executes(c -> {
                            int n = 0;
                            for (Entity e : c.getSource().getLevel().getAllEntities()) {
                                if (e instanceof VerityMonster m) { m.setState(IntegerArgumentType.getInteger(c, "state")); n++; }
                            }
                            return n;
                        })))
                .then(Commands.literal("clear").executes(c -> {
                    int n = 0;
                    for (Entity e : c.getSource().getLevel().getAllEntities()) {
                        if (e.getType().builtInRegistryHolder().key().location().getNamespace().equals("verity")) { e.discard(); n++; }
                    }
                    final int count = n;
                    c.getSource().sendSuccess(() -> Component.literal("Removed " + count + " Verity entities"), false);
                    return n;
                })));
    }

    private static final String[] HAUNTS = {"steps", "whisper", "knock", "stare", "join", "watcher", "behind", "dark", "flash", "echo", "visit", "nightmare", "crawl", "window", "doorway", "rush", "rise"};

    private static int wall(CommandContext<CommandSourceStack> c, int which) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        boolean ok = Story.get(c.getSource().getLevel()).wallScare(c.getSource().getPlayerOrException(), which);
        if (!ok) c.getSource().sendFailure(Component.literal("Не разрешено на экране при входе в мир"));
        return ok ? 1 : 0;
    }

    private interface BallAction { void run(VerityBall ball); }

    private static int forBalls(CommandContext<CommandSourceStack> c, BallAction action) {
        int n = 0;
        for (Entity e : c.getSource().getLevel().getAllEntities()) {
            if (e instanceof VerityBall b) { action.run(b); n++; }
        }
        return n;
    }

    private static int summon(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 fwd = Vec3.directionFromRotation(0, src.getRotation().y);
        Vec3 pos = src.getPosition().add(fwd.scale(4.0));
        EntityType<?> type = switch (StringArgumentType.getString(c, "what")) {
            case "monster" -> ModEntities.MONSTER.get();
            case "box" -> ModEntities.BOX.get();
            default -> ModEntities.BALL.get();
        };
        Entity e = type.create(level);
        if (e == null) return 0;
        e.moveTo(pos.x, pos.y + (e instanceof VerityBall ? 1.2 : 0), pos.z, src.getRotation().y + 180f, 0f);
        if (e instanceof VerityBall ball && src.getEntity() != null) ball.setOwner(src.getEntity().getUUID());
        if (e instanceof net.minecraft.world.entity.LivingEntity le) { le.yHeadRot = le.getYRot(); le.yBodyRot = le.getYRot(); }
        level.addFreshEntity(e);
        src.sendSuccess(() -> Component.literal("Summoned " + e.getType().toShortString()), false);
        return 1;
    }

    private VerityCommand() {}
}

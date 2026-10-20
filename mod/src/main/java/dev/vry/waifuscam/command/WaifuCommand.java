package dev.vry.waifuscam.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import dev.vry.waifuscam.WaifuScamMod;
import dev.vry.waifuscam.bot.CompanionManager;
import dev.vry.waifuscam.bot.CompanionPlayer;
import dev.vry.waifuscam.skill.Skill;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * /waifu status   — conexão com a Mente e estado da personagem
 * /waifu ping     — latência até a Mente
 * /waifu spawn    — coloca a personagem no mundo, perto de você
 * /waifu despawn  — tira a personagem do mundo (o inventário fica salvo)
 * /waifu follow [player] — ela segue você (ou outro player)
 * /waifu come     — ela vem até você
 * /waifu goto x y z — ela vai até o ponto
 * /waifu stop     — ela para o que está fazendo
 * /waifu obtain|craft|collect <item> [n] — ela consegue, crafta ou coleta itens
 * /waifu give <item> [n] — ela te entrega itens
 * /waifu eat | deposit | inv — comer, guardar no baú, ver o inventário
 * /waifu shelter | attack | hunt <bicho> — abrigo para a noite, lutar, caçar
 * /waifu reflexes on|off — liga/desliga os reflexos (fugir, revidar, comer)
 * /waifu remember <nome> — ela guarda na memória o lugar onde você está
 */
public final class WaifuCommand {
	private static final AtomicInteger COMMAND_IDS = new AtomicInteger();

	private WaifuCommand() {}

	private static int run(CommandSourceStack src, WaifuScamMod mod, String skill, JsonObject args, String ok) {
		String error = mod.startSkill("cmd-" + COMMAND_IDS.incrementAndGet(), skill, args);
		if (error != null) {
			src.sendFailure(Component.literal("Não deu: " + error + "."));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(ok), false);
		return 1;
	}

	private static JsonObject playerArg(String name) {
		JsonObject args = new JsonObject();
		args.addProperty("player", name);
		return args;
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, WaifuScamMod mod) {
		dispatcher.register(Commands.literal("waifu")
				.then(Commands.literal("status").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					if (!mod.isMindConnected()) {
						src.sendFailure(Component.literal(
								"Mente desconectada. Rode a Mente e confira mind_url em config/waifuscam.json (" + mod.mindUrl() + ")."));
						return 0;
					}
					String name = mod.characterName() != null ? mod.characterName() : "aguardando welcome";
					String ping = mod.lastPingMillis() >= 0 ? mod.lastPingMillis() + " ms" : "sem medida";
					src.sendSuccess(() -> Component.literal("Mente conectada. Personagem: " + name + ". Ping: " + ping), false);

					CompanionManager companions = mod.companions();
					CompanionPlayer p = companions != null ? companions.player() : null;
					Skill doing = companions != null ? companions.currentSkill() : null;
					String body = p == null
							? "fora do mundo (/waifu spawn para chamar)"
							: String.format("no mundo, vida %.0f/%.0f, fome %d/20, %s", p.getHealth(), p.getMaxHealth(),
									p.getFoodData().getFoodLevel(), doing == null ? "parada" : doing.describe());
					src.sendSuccess(() -> Component.literal("Corpo: " + body), false);
					return 1;
				}))
				.then(Commands.literal("ping").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					boolean sent = mod.ping(ms -> src.sendSuccess(() -> Component.literal("pong da Mente em " + ms + " ms"), false));
					if (!sent) {
						src.sendFailure(Component.literal("Mente desconectada."));
						return 0;
					}
					return 1;
				}))
				.then(Commands.literal("spawn").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					CompanionManager companions = mod.companions();
					String error = companions == null ? "servidor ainda iniciando" : companions.spawn(src.getPlayer());
					if (error != null) {
						src.sendFailure(Component.literal("Não deu: " + error + "."));
						return 0;
					}
					return 1;
				}))
				.then(Commands.literal("despawn").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					CompanionManager companions = mod.companions();
					if (companions == null || !companions.despawn("saiu do jogo")) {
						src.sendFailure(Component.literal("Ela não está no mundo."));
						return 0;
					}
					return 1;
				}))
				.then(Commands.literal("follow")
						.executes(ctx -> {
							ServerPlayer me = ctx.getSource().getPlayerOrException();
							String name = me.getGameProfile().name();
							return run(ctx.getSource(), mod, "follow", playerArg(name), "Ela está seguindo você.");
						})
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										ctx.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder))
								.executes(ctx -> {
									String name = StringArgumentType.getString(ctx, "player");
									return run(ctx.getSource(), mod, "follow", playerArg(name), "Ela está seguindo " + name + ".");
								})))
				.then(Commands.literal("come").executes(ctx -> {
					ServerPlayer me = ctx.getSource().getPlayerOrException();
					return run(ctx.getSource(), mod, "goto", playerArg(me.getGameProfile().name()), "Ela está vindo.");
				}))
				.then(Commands.literal("goto")
						.then(Commands.argument("pos", Vec3Argument.vec3()).executes(ctx -> {
							Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
							JsonObject args = new JsonObject();
							args.addProperty("x", pos.x);
							args.addProperty("y", pos.y);
							args.addProperty("z", pos.z);
							return run(ctx.getSource(), mod, "goto", args, "Ela está indo.");
						})))
				.then(Commands.literal("stop").executes(ctx -> {
					CompanionManager companions = mod.companions();
					if (companions == null || !companions.stopSkill()) {
						ctx.getSource().sendFailure(Component.literal("Ela já está parada."));
						return 0;
					}
					ctx.getSource().sendSuccess(() -> Component.literal("Ela parou."), false);
					return 1;
				}))
				// ---- fase 4: mãos ----
				.then(itemSkill("obtain", "Ela vai conseguir", mod, context))
				.then(itemSkill("craft", "Ela vai craftar", mod, context))
				.then(itemSkill("collect", "Ela vai coletar", mod, context))
				.then(Commands.literal("give")
						.then(Commands.argument("item", ItemArgument.item(context))
								.executes(ctx -> give(ctx, mod, 1))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 576))
										.executes(ctx -> give(ctx, mod, IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("eat").executes(ctx ->
						run(ctx.getSource(), mod, "eat", new JsonObject(), "Ela vai comer.")))
				.then(Commands.literal("deposit").executes(ctx ->
						run(ctx.getSource(), mod, "deposit", new JsonObject(), "Ela vai guardar as coisas no baú.")))
				// ---- fase 5: sobreviver ----
				.then(Commands.literal("shelter").executes(ctx ->
						run(ctx.getSource(), mod, "shelter", new JsonObject(), "Ela vai se abrigar.")))
				.then(Commands.literal("attack").executes(ctx ->
						run(ctx.getSource(), mod, "attack", new JsonObject(), "Ela vai atacar os monstros.")))
				.then(Commands.literal("hunt")
						.then(Commands.argument("animal", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[] {"cow", "pig", "sheep", "chicken", "rabbit"}, builder))
								.executes(ctx -> {
									JsonObject args = new JsonObject();
									args.addProperty("animal", StringArgumentType.getString(ctx, "animal"));
									return run(ctx.getSource(), mod, "hunt", args, "Ela vai caçar.");
								})))
				.then(Commands.literal("reflexes")
						.then(Commands.literal("on").executes(ctx -> reflexes(ctx.getSource(), mod, true)))
						.then(Commands.literal("off").executes(ctx -> reflexes(ctx.getSource(), mod, false))))
				// ---- fase 6: memória ----
				.then(Commands.literal("remember")
						.then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
							CommandSourceStack src = ctx.getSource();
							if (!mod.isMindConnected()) {
								src.sendFailure(Component.literal("A Mente não está conectada."));
								return 0;
							}
							String name = StringArgumentType.getString(ctx, "name");
							Vec3 pos = src.getPosition();
							JsonObject fields = new JsonObject();
							fields.addProperty("name", name);
							fields.addProperty("x", pos.x);
							fields.addProperty("y", pos.y);
							fields.addProperty("z", pos.z);
							fields.addProperty("dimension", src.getLevel().dimension().identifier().toString());
							fields.addProperty("from", src.getTextName());
							mod.sendMindEvent("remember_place", fields);
							src.sendSuccess(() -> Component.literal("Ela vai lembrar deste lugar como \"" + name + "\"."), false);
							return 1;
						})))
				.then(Commands.literal("inv").executes(ctx -> {
					CompanionManager companions = mod.companions();
					CompanionPlayer p = companions == null ? null : companions.player();
					if (p == null) {
						ctx.getSource().sendFailure(Component.literal("Ela não está no mundo."));
						return 0;
					}
					StringBuilder line = new StringBuilder("Inventário: ");
					Stacks.summary(p.getInventory(), 40).forEach((item, n) -> line.append(n).append(' ').append(item).append(", "));
					String text = line.length() > 12 ? line.substring(0, line.length() - 2) : "Inventário vazio";
					ctx.getSource().sendSuccess(() -> Component.literal(text), false);
					return 1;
				})));
	}

	private static int reflexes(CommandSourceStack src, WaifuScamMod mod, boolean on) {
		CompanionManager companions = mod.companions();
		if (companions == null) {
			return 0;
		}
		companions.setReflexes(on);
		src.sendSuccess(() -> Component.literal(on ? "Reflexos ligados: ela foge, revida e come sozinha."
				: "Reflexos desligados (para testar)."), false);
		return 1;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> itemSkill(
			String skill, String ok, WaifuScamMod mod, CommandBuildContext context) {
		return Commands.literal(skill)
				.then(Commands.argument("item", ItemArgument.item(context))
						.executes(ctx -> itemSkillRun(ctx, skill, ok, mod, 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 576))
								.executes(ctx -> itemSkillRun(ctx, skill, ok, mod, IntegerArgumentType.getInteger(ctx, "count")))));
	}

	private static int itemSkillRun(CommandContext<CommandSourceStack> ctx, String skill, String ok, WaifuScamMod mod, int count) {
		String item = Stacks.id(ItemArgument.getItem(ctx, "item").item().value());
		JsonObject args = new JsonObject();
		args.addProperty("item", item);
		args.addProperty("count", count);
		return run(ctx.getSource(), mod, skill, args, ok + " " + count + " " + item.replace("minecraft:", "") + ".");
	}

	private static int give(CommandContext<CommandSourceStack> ctx, WaifuScamMod mod, int count) throws CommandSyntaxException {
		ServerPlayer me = ctx.getSource().getPlayerOrException();
		String item = Stacks.id(ItemArgument.getItem(ctx, "item").item().value());
		JsonObject args = playerArg(me.getGameProfile().name());
		args.addProperty("item", item);
		args.addProperty("count", count);
		return run(ctx.getSource(), mod, "give", args, "Ela vai te dar " + count + " " + item.replace("minecraft:", "") + ".");
	}
}

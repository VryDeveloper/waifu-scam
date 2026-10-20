package dev.vry.waifuscam.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, WaifuScamMod mod) {
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
				})));
	}
}

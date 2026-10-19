package dev.vry.waifuscam.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.vry.waifuscam.WaifuScamMod;
import dev.vry.waifuscam.bot.CompanionManager;
import dev.vry.waifuscam.bot.CompanionPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /waifu status   — conexão com a Mente e estado da personagem
 * /waifu ping     — latência até a Mente
 * /waifu spawn    — coloca a personagem no mundo, perto de você
 * /waifu despawn  — tira a personagem do mundo (o inventário fica salvo)
 */
public final class WaifuCommand {
	private WaifuCommand() {}

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
					String body = p == null
							? "fora do mundo (/waifu spawn para chamar)"
							: String.format("no mundo, vida %.0f/%.0f, fome %d/20", p.getHealth(), p.getMaxHealth(),
									p.getFoodData().getFoodLevel());
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
				})));
	}
}

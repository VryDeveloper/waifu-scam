package dev.vry.waifuscam.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.vry.waifuscam.WaifuScamMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /waifu status — mostra se a Mente está conectada e qual personagem ela carregou.
 * /waifu ping   — mede a latência até a Mente.
 */
public final class WaifuCommand {
	private WaifuCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, WaifuScamMod mod) {
		dispatcher.register(Commands.literal("waifu")
				.then(Commands.literal("status").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					if (mod.isMindConnected()) {
						String name = mod.characterName() != null ? mod.characterName() : "aguardando welcome";
						String ping = mod.lastPingMillis() >= 0 ? mod.lastPingMillis() + " ms" : "sem medida";
						src.sendSuccess(() -> Component.literal(
								"Mente conectada (" + mod.mindUrl() + "). Personagem: " + name + ". Último ping: " + ping), false);
					} else {
						src.sendFailure(Component.literal(
								"Mente desconectada. Rode a Mente e confira mind_url em config/waifuscam.json (" + mod.mindUrl() + ")."));
					}
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
				})));
	}
}

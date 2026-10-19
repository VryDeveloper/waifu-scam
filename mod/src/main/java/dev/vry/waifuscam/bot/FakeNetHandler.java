package dev.vry.waifuscam.bot;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * Lado servidor da "conexão" da personagem.
 *
 * Adaptado do NetHandlerPlayServerFake do Carpet (MIT, gnembon).
 */
public class FakeNetHandler extends ServerGamePacketListenerImpl {
	public FakeNetHandler(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		super(server, connection, player, cookie);
	}

	@Override
	public void disconnect(Component message) {
		// Ela nunca é expulsa por ficar parada. Qualquer outro motivo (/kick,
		// servidor fechando, despawn) remove a personagem como um player saindo.
		if (message.getContents() instanceof TranslatableContents text
				&& text.getKey().equals("multiplayer.disconnect.idling")) {
			return;
		}
		onDisconnect(new DisconnectionDetails(message));
	}

	@Override
	public void teleport(PositionMoveRotation positionMoveRotation, Set<Relative> relatives) {
		super.teleport(positionMoveRotation, relatives);
		if (player.level().getPlayerByUUID(player.getUUID()) != null) {
			resetPosition();
			player.level().getChunkSource().move(player);
		}
	}
}

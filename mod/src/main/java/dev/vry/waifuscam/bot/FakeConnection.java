package dev.vry.waifuscam.bot;

import dev.vry.waifuscam.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Conexão de rede que não vai a lugar nenhum: a personagem não tem cliente,
 * então todo pacote enviado para ela é descartado.
 *
 * Adaptado do FakeClientConnection do Carpet (MIT, gnembon).
 */
public class FakeConnection extends Connection {
	public FakeConnection(PacketFlow flow) {
		super(flow);
		// Um canal "de mentira" faz isConnected() responder true, o que alguns
		// trechos do jogo exigem (ex.: teleporte de pérola do End).
		((ConnectionAccessor) this).waifuscam$setChannel(new EmbeddedChannel());
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void setListenerForServerboundHandshake(PacketListener listener) {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
	}
}

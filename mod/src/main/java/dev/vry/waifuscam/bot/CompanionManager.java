package dev.vry.waifuscam.bot;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Cuida do ciclo de vida da personagem: entrar, sair, morrer e renascer.
 *
 * Guarda a personagem pelo UUID, não pela instância: a cada respawn o jogo
 * cria um ServerPlayer novo.
 */
public final class CompanionManager {
	private final MinecraftServer server;
	private final BiConsumer<String, String> events; // (evento, detalhe) → Mente

	private String username;
	private SkinData skin;
	private UUID uuid;

	public CompanionManager(MinecraftServer server, BiConsumer<String, String> events) {
		this.server = server;
		this.events = events;
	}

	/** Chamado quando a Mente diz qual personagem é (welcome). */
	public void configure(String username, SkinData skin) {
		this.username = username;
		this.skin = skin;
	}

	public boolean isConfigured() {
		return username != null;
	}

	public String username() {
		return username;
	}

	public CompanionPlayer player() {
		if (uuid == null) {
			return null;
		}
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		return p instanceof CompanionPlayer c ? c : null;
	}

	public boolean isOnline() {
		return player() != null;
	}

	/**
	 * Coloca a personagem no mundo perto de near (ou no spawn do mundo, se null).
	 * Devolve uma mensagem de erro, ou null se deu certo. Precisa rodar na thread do servidor.
	 */
	public String spawn(ServerPlayer near) {
		if (!isConfigured()) {
			return "a Mente ainda não mandou a personagem (ela está rodando?)";
		}
		if (isOnline()) {
			return username + " já está no mundo";
		}
		if (server.getPlayerList().getPlayerByName(username) != null) {
			return "já existe um player chamado " + username;
		}

		ServerLevel level;
		Vec3 pos;
		float yaw;
		if (near != null) {
			level = near.level();
			Vec3 look = Vec3.directionFromRotation(0, near.getYRot()).scale(2);
			pos = near.position().add(look.x, 0, look.z); // dois blocos à frente de quem chamou
			yaw = near.getYRot() + 180; // olhando para ele
		} else {
			level = server.overworld();
			pos = Vec3.atBottomCenterOf(level.getLevelData().getRespawnData().pos());
			yaw = 0;
		}

		CompanionPlayer player = CompanionPlayer.spawn(server, level, CompanionPlayer.profile(username, skin), pos, yaw);
		uuid = player.getUUID();
		events.accept("spawned", null);
		return null;
	}

	/** Tira a personagem do mundo, como um player saindo (o inventário fica salvo). */
	public boolean despawn(String reason) {
		CompanionPlayer player = player();
		if (player == null) {
			return false;
		}
		player.connection.disconnect(Component.literal(reason));
		events.accept("despawned", reason);
		return true;
	}

	public void onDied(CompanionPlayer player, String deathMessage) {
		events.accept("died", deathMessage);
	}

	public void onRespawned(CompanionPlayer player) {
		events.accept("respawned", null);
	}
}

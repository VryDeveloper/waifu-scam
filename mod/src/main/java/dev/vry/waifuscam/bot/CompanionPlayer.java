package dev.vry.waifuscam.bot;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.vry.waifuscam.WaifuScamMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A personagem dentro do mundo: um ServerPlayer de verdade, sem cliente.
 *
 * Por ser um player, ela tem vida, fome, inventário, dano, morte e aparece no Tab
 * sem nenhum código extra. Diferente do fake player do Carpet, ela não some ao
 * morrer: o CompanionManager espera um pouco e aperta "Respawn" por ela.
 *
 * Partes adaptadas do EntityPlayerMPFake do Carpet (MIT, gnembon).
 */
public class CompanionPlayer extends ServerPlayer {
	public Runnable fixStartingPosition = () -> {};

	private CompanionPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
		super(server, level, profile, info);
	}

	/** Perfil com UUID de modo offline (estável pelo nome) e, se houver, a skin assinada. */
	public static GameProfile profile(String username, SkinData skin) {
		UUID uuid = UUIDUtil.createOfflinePlayerUUID(username);
		if (skin == null || !skin.isValid()) {
			return new GameProfile(uuid, username);
		}
		Property textures = new Property("textures", skin.value(), skin.signature());
		return new GameProfile(uuid, username, new PropertyMap(ImmutableMultimap.of("textures", textures)));
	}

	/** Cria a personagem e a coloca no mundo, em pos, como se ela tivesse entrado no servidor. */
	public static CompanionPlayer spawn(MinecraftServer server, ServerLevel level, GameProfile profile, Vec3 pos, float yaw) {
		CompanionPlayer player = new CompanionPlayer(server, level, profile, ClientInformation.createDefault());
		player.fixStartingPosition = () -> player.snapTo(pos.x, pos.y, pos.z, yaw, 0);
		server.getPlayerList().placeNewPlayer(new FakeConnection(PacketFlow.SERVERBOUND), player,
				new CommonListenerCookie(profile, 0, player.clientInformation(), false));

		// Inventário, vida e fome salvos da última sessão, se existirem.
		loadPlayerData(player);
		player.stopRiding();
		// Ela sempre entra perto de quem abriu o mundo, não onde saiu da última vez.
		player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, 0, true);
		if (player.isDeadOrDying()) {
			player.setHealth(player.getMaxHealth());
		}
		player.unsetRemoved();
		player.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
		player.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // mostra todas as camadas da skin

		server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(player, (byte) (player.yHeadRot * 256 / 360)), level.dimension());
		server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(player), level.dimension());
		return player;
	}

	/** Usado pelo mixin de PlayerList.respawn: depois de morrer, ela renasce como CompanionPlayer. */
	public static CompanionPlayer respawnCopy(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
		return new CompanionPlayer(server, level, profile, info);
	}

	private static void loadPlayerData(CompanionPlayer player) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), WaifuScamMod.LOGGER)) {
			Optional<ValueInput> data = player.level().getServer().getPlayerList().loadPlayerData(player.nameAndId())
					.map(tag -> TagValueInput.create(reporter, player.registryAccess(), tag));
			data.ifPresent(input -> {
				player.load(input);
				player.loadAndSpawnEnderPearls(input);
				player.loadAndSpawnParentVehicle(input);
			});
		}
	}

	@Override
	public void tick() {
		MinecraftServer server = level().getServer();
		// Sem cliente, ninguém confirma a posição: fazemos isso aqui de tempos em tempos.
		if (server.getTickCount() % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);
		}
		// As "teclas" que as habilidades apertaram no tick anterior.
		CompanionManager manager = WaifuScamMod.instance().companions();
		if (manager != null) {
			manager.controls().applyTo(this);
		}
		super.tick();
		doTick();
	}

	/** Aperta o botão "Respawn", pelo mesmo caminho que um cliente de verdade usaria. */
	public void pressRespawn() {
		connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
	}

	@Override
	public void die(DamageSource cause) {
		// Ninguém fica montado nela depois da morte.
		if (getVehicle() instanceof Player) {
			stopRiding();
		}
		for (Entity passenger : getIndirectPassengers()) {
			if (passenger instanceof Player) {
				passenger.stopRiding();
			}
		}
		String message = getCombatTracker().getDeathMessage().getString();
		super.die(cause); // solta os itens e mostra a mensagem de morte, como um player normal
		WaifuScamMod.instance().companions().onDied(this, message);
	}

	@Override
	public String getIpAddress() {
		return "127.0.0.1";
	}
}

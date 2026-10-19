package dev.vry.waifuscam.mixin;

import com.mojang.authlib.GameProfile;
import dev.vry.waifuscam.WaifuScamMod;
import dev.vry.waifuscam.bot.CompanionPlayer;
import dev.vry.waifuscam.bot.FakeNetHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Três ajustes na lista de players para a personagem:
 * posição inicial, conexão falsa e respawn como CompanionPlayer.
 *
 * Adaptado do PlayerList_fakePlayersMixin do Carpet (MIT, gnembon).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Inject(method = "placeNewPlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/server/level/ServerLevel;"))
	private void waifuscam$fixStartingPos(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
		if (player instanceof CompanionPlayer companion) {
			companion.fixStartingPosition.run();
		}
	}

	@Redirect(method = "placeNewPlayer", at = @At(value = "NEW",
			target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
	private ServerGamePacketListenerImpl waifuscam$fakeNetHandler(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		if (player instanceof CompanionPlayer) {
			return new FakeNetHandler(this.server, connection, player, cookie);
		}
		return new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
	}

	@Redirect(method = "respawn", at = @At(value = "NEW",
			target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
	private ServerPlayer waifuscam$respawnAsCompanion(MinecraftServer server, ServerLevel level, GameProfile profile,
			ClientInformation info, ServerPlayer oldPlayer, boolean keepAllPlayerData) {
		if (oldPlayer instanceof CompanionPlayer) {
			CompanionPlayer fresh = CompanionPlayer.respawnCopy(server, level, profile, info);
			WaifuScamMod.instance().companions().onRespawned(fresh);
			return fresh;
		}
		return new ServerPlayer(server, level, profile, info);
	}
}

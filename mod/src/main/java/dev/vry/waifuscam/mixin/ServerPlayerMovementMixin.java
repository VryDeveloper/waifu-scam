package dev.vry.waifuscam.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;
import dev.vry.waifuscam.bot.CompanionPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ServerPlayer usa o último movimento informado pelo cliente; sem cliente, usamos
 * o movimento calculado no servidor.
 *
 * Adaptado do ServerPlayer_fakeLastMovementMixin do Carpet (MIT, gnembon).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMovementMixin extends Player {
	public ServerPlayerMovementMixin(Level level, GameProfile profile) {
		super(level, profile);
		throw new AssertionError();
	}

	@ModifyExpressionValue(
			method = {"getKnownMovement", "getKnownSpeed"},
			at = @At(value = "FIELD",
					target = "Lnet/minecraft/server/level/ServerPlayer;lastKnownClientMovement:Lnet/minecraft/world/phys/Vec3;",
					opcode = Opcodes.GETFIELD),
			require = 2)
	private Vec3 waifuscam$serverMovement(Vec3 original) {
		return ((Player) this) instanceof CompanionPlayer ? super.getKnownMovement() : original;
	}
}

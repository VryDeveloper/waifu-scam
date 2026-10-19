package dev.vry.waifuscam.mixin;

import dev.vry.waifuscam.bot.CompanionPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Faz o empurrão de um ataque funcionar nela.
 *
 * Adaptado do Player_fakePlayersMixin do Carpet (MIT, gnembon).
 */
@Mixin(Player.class)
public abstract class PlayerKnockbackMixin {
	@Redirect(method = "causeExtraKnockback", at = @At(value = "FIELD",
			target = "Lnet/minecraft/world/entity/Entity;syncVelocity:Z", ordinal = 0))
	private boolean waifuscam$knockbackCompanion(Entity target) {
		return target.syncVelocity && !(target instanceof CompanionPlayer);
	}
}

package dev.vry.waifuscam.mixin;

import dev.vry.waifuscam.bot.CompanionPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Num player comum, quem calcula a física do movimento é o cliente. A personagem
 * não tem cliente, então o servidor passa a ser a autoridade sobre ela.
 *
 * Adaptado do EntityMixin do Carpet (MIT, gnembon).
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Shadow
	public Level level;

	@Shadow
	public abstract @Nullable LivingEntity getControllingPassenger();

	@Inject(method = "isLocalInstanceAuthoritative", at = @At("HEAD"), cancellable = true)
	private void waifuscam$serverIsAuthority(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof CompanionPlayer || getControllingPassenger() instanceof CompanionPlayer) {
			cir.setReturnValue(!level.isClientSide());
		}
	}
}

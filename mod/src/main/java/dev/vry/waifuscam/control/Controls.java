package dev.vry.waifuscam.control;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * As "teclas" da personagem: o que um player apertaria no teclado e no mouse.
 *
 * As habilidades escrevem aqui durante o tick do servidor; no começo do tick
 * seguinte o CompanionPlayer aplica no corpo, antes da física rodar — o mesmo
 * caminho do input de um cliente de verdade.
 */
public final class Controls {
	private float forward;
	private float strafe;
	private boolean jump;
	private boolean sprint;
	private boolean sneak;
	private Vec3 lookAt;

	/** Solta tudo. Chamado antes de cada tick das habilidades. */
	public void release() {
		forward = 0;
		strafe = 0;
		jump = false;
		sprint = false;
		sneak = false;
		lookAt = null;
	}

	public Controls forward(float value) {
		forward = Math.max(-1, Math.min(1, value));
		return this;
	}

	public Controls strafe(float value) {
		strafe = Math.max(-1, Math.min(1, value));
		return this;
	}

	public Controls jump(boolean value) {
		jump = value;
		return this;
	}

	public Controls sprint(boolean value) {
		sprint = value;
		return this;
	}

	public Controls sneak(boolean value) {
		sneak = value;
		return this;
	}

	/** Vira o rosto (e a direção de andar) para este ponto. */
	public Controls lookAt(Vec3 point) {
		lookAt = point;
		return this;
	}

	public boolean isMoving() {
		return forward != 0 || strafe != 0 || jump;
	}

	public void applyTo(ServerPlayer player) {
		if (lookAt != null && player.getEyePosition().distanceToSqr(lookAt) > 1.0E-4) {
			player.lookAt(EntityAnchorArgument.Anchor.EYES, lookAt);
		}
		float speed = sneak ? 0.3F : 1.0F;
		player.zza = forward * speed;
		player.xxa = strafe * speed;
		player.setJumping(jump);
		player.setShiftKeyDown(sneak);
		player.setSprinting(sprint && forward > 0 && !sneak && player.getFoodData().getFoodLevel() > 6);
	}
}

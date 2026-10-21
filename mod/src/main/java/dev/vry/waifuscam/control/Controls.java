package dev.vry.waifuscam.control;

import net.minecraft.util.Mth;
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

	/** Velocidade máxima de giro por tick (graus). Um player vira rápido, mas não teleporta o olhar. */
	private static final float MAX_YAW_STEP = 28F;
	private static final float MAX_PITCH_STEP = 18F;

	/**
	 * Gira a cabeça em direção ao ponto, suavizando (mais rápido quando longe do alvo,
	 * desacelerando no fim). Devolve quantos graus ainda faltavam antes deste passo.
	 */
	static float turnTowards(ServerPlayer player, Vec3 point) {
		float[] target = angles(player, point);
		float yawDiff = Mth.degreesDifference(player.getYRot(), target[0]);
		float pitchDiff = target[1] - player.getXRot();
		float yawStep = Math.min(MAX_YAW_STEP, Math.max(3F, Math.abs(yawDiff) * 0.45F));
		float pitchStep = Math.min(MAX_PITCH_STEP, Math.max(2F, Math.abs(pitchDiff) * 0.45F));
		float yaw = Mth.approachDegrees(player.getYRot(), target[0], yawStep);
		float pitch = Mth.approach(player.getXRot(), target[1], pitchStep);
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setYHeadRot(yaw);
		return Math.abs(yawDiff);
	}

	/** [yaw, pitch] para olhar do olho dela até o ponto. */
	static float[] angles(ServerPlayer player, Vec3 point) {
		Vec3 d = point.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F);
		float pitch = Mth.wrapDegrees((float) (-(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG)));
		return new float[] {yaw, pitch};
	}

	/** Ela já está olhando para o ponto (com folga de `tolerance` graus)? */
	public static boolean aimed(ServerPlayer player, Vec3 point, float tolerance) {
		float[] target = angles(player, point);
		return Math.abs(Mth.degreesDifference(player.getYRot(), target[0])) <= tolerance
				&& Math.abs(target[1] - player.getXRot()) <= tolerance;
	}

	public boolean isMoving() {
		return forward != 0 || strafe != 0 || jump;
	}

	public void applyTo(ServerPlayer player) {
		if (player.isSleeping()) {
			player.zza = 0;
			player.xxa = 0;
			player.setJumping(false);
			return;
		}
		float turning = 0;
		if (lookAt != null && player.getEyePosition().distanceToSqr(lookAt) > 1.0E-4) {
			turning = turnTowards(player, lookAt);
		}
		float speed = sneak ? 0.3F : 1.0F;
		// Virando muito ainda: anda devagar, como alguém que se vira antes de sair andando.
		if (turning > 70) {
			speed *= 0.2F;
		} else if (turning > 35) {
			speed *= 0.6F;
		}
		player.zza = forward * speed;
		player.xxa = strafe * speed;
		player.setJumping(jump);
		player.setShiftKeyDown(sneak);
		player.setSprinting(sprint && forward > 0 && !sneak && player.getFoodData().getFoodLevel() > 6);
	}
}

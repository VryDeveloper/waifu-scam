package dev.vry.waifuscam.hands;

import dev.vry.waifuscam.bot.CompanionPlayer;
import dev.vry.waifuscam.control.Controls;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Atirar com arco como um player: puxa a corda (segura o botão), mira um pouco
 * acima e à frente do bicho (a flecha cai e ele anda) e solta com o arco cheio.
 *
 * Nunca solta se tiver um player no caminho da flecha.
 */
public final class Archery {
	/** Distância boa para o arco: perto disso é melhor a espada. */
	public static final double MIN_RANGE = 5;
	public static final double MAX_RANGE = 24;
	private static final int FULL_DRAW = 20; // ticks até o arco ficar cheio
	private static final double ARROW_SPEED = 3.0; // blocos por tick com o arco cheio
	private static final double GRAVITY = 0.05;

	private LivingEntity target;

	public static boolean canShoot(ServerPlayer me) {
		return Stacks.find(me.getInventory(), s -> s.is(Items.BOW) && !Tools.almostBroken(s)) >= 0
				&& (Stacks.find(me.getInventory(), s -> s.is(ItemTags.ARROWS)) >= 0 || me.getAbilities().instabuild);
	}

	/** Ela está com o arco puxado agora. */
	public static boolean drawing(ServerPlayer me) {
		return me.isUsingItem() && me.getUseItem().is(Items.BOW);
	}

	/** Larga o arco sem atirar (vai trocar para a espada, fugir...). */
	public void cancel(ServerPlayer me) {
		if (drawing(me)) {
			me.stopUsingItem();
		}
		target = null;
	}

	/** Um tick de tiro no alvo. Devolve true se soltou a flecha neste tick. */
	public boolean tick(ServerPlayer me, LivingEntity t, Controls controls) {
		if (target != t) {
			cancel(me);
			target = t;
		}
		Vec3 aim = aimPoint(me, t);
		controls.lookAt(aim);
		if (!drawing(me)) {
			int slot = Stacks.find(me.getInventory(), s -> s.is(Items.BOW) && !Tools.almostBroken(s));
			if (slot < 0) {
				return false;
			}
			Stacks.hold(me.getInventory(), slot);
			me.gameMode.useItem(me, me.level(), me.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			return false;
		}
		me.resetLastActionTime();
		if (me.getTicksUsingItem() < FULL_DRAW || !Controls.aimed(me, aim, 3) || !me.hasLineOfSight(t)) {
			return false;
		}
		if (friendInTheWay(me, t)) {
			return false; // segura o tiro até o caminho ficar livre
		}
		me.releaseUsingItem();
		target = null;
		return true;
	}

	/** Para onde mirar: onde o bicho vai estar quando a flecha chegar, compensando a queda. */
	static Vec3 aimPoint(ServerPlayer me, LivingEntity t) {
		Vec3 eye = me.getEyePosition();
		Vec3 at = t.getBoundingBox().getCenter();
		double flight = at.distanceTo(eye) / ARROW_SPEED;
		at = at.add(t.getDeltaMovement().multiply(flight, 0, flight));
		Vec3 d = at.subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		double v2 = ARROW_SPEED * ARROW_SPEED;
		double disc = v2 * v2 - GRAVITY * (GRAVITY * horizontal * horizontal + 2 * d.y * v2);
		double angle = disc < 0 ? Math.PI / 4 : Math.atan((v2 - Math.sqrt(disc)) / (GRAVITY * Math.max(horizontal, 0.1)));
		// Ponto a 10 blocos na direção do tiro (o Controls vira a cabeça até lá).
		double yaw = Math.atan2(d.z, d.x);
		return eye.add(Math.cos(yaw) * Math.cos(angle) * 10, Math.sin(angle) * 10, Math.sin(yaw) * Math.cos(angle) * 10);
	}

	/** Algum player (sem ser ela) perto da linha do tiro? */
	static boolean friendInTheWay(ServerPlayer me, LivingEntity t) {
		Vec3 from = me.getEyePosition();
		Vec3 to = t.getBoundingBox().getCenter();
		AABB area = new AABB(from, to).inflate(1.5);
		for (Player p : me.level().getEntitiesOfClass(Player.class, area, p -> p != me && !(p instanceof CompanionPlayer))) {
			Vec3 c = p.getBoundingBox().getCenter();
			Vec3 seg = to.subtract(from);
			double k = Math.max(0, Math.min(1, c.subtract(from).dot(seg) / seg.lengthSqr()));
			if (from.add(seg.scale(k)).distanceTo(c) < 1.3) {
				return true;
			}
		}
		return false;
	}
}

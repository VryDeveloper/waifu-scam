package dev.vry.waifuscam.hands;

import dev.vry.waifuscam.control.Controls;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Lutar como um player: arma certa na mão, esperar a recarga, bater. */
public final class Combat {
	/** Distância em que ela consegue bater (alcance de player é 3). */
	public static final double REACH = 2.9;

	private static final List<String> MATERIALS = List.of("netherite", "diamond", "iron", "copper", "stone", "golden", "wooden");

	private Combat() {}

	/** Bicho que quer machucar ela agora. */
	public static boolean isHostile(Entity e, ServerPlayer me) {
		if (!(e instanceof LivingEntity living) || !living.isAlive() || e == me) {
			return false;
		}
		if (e instanceof Mob mob && mob.getTarget() == me) {
			return true; // qualquer um que está mirando nela (lobo bravo, golem)
		}
		if (e instanceof NeutralMob) {
			return false; // enderman e piglin zumbi só se provocados
		}
		return e instanceof Enemy;
	}

	public static Optional<LivingEntity> nearestHostile(ServerPlayer me, double radius) {
		AABB box = me.getBoundingBox().inflate(radius);
		return me.level().getEntitiesOfClass(LivingEntity.class, box, e -> isHostile(e, me) && me.hasLineOfSight(e))
				.stream()
				.filter(e -> e.distanceTo(me) <= radius)
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
	}

	/** Espada boa primeiro, depois machado; nada = mão. */
	public static void equipWeapon(ServerPlayer me) {
		Inventory inv = me.getInventory();
		int best = -1;
		int bestScore = Integer.MAX_VALUE;
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || Tools.almostBroken(s)) {
				continue;
			}
			boolean sword = s.is(ItemTags.SWORDS);
			boolean axe = s.is(ItemTags.AXES);
			if (!sword && !axe) {
				continue;
			}
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			int material = MATERIALS.size();
			for (int m = 0; m < MATERIALS.size(); m++) {
				if (id.startsWith(MATERIALS.get(m))) {
					material = m;
					break;
				}
			}
			int score = material * 2 + (sword ? 0 : 1);
			if (score < bestScore) {
				best = i;
				bestScore = score;
			}
		}
		if (best >= 0) {
			Stacks.hold(inv, best);
		}
	}

	public static boolean hasWeapon(ServerPlayer me) {
		return Stacks.find(me.getInventory(), s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) >= 0;
	}

	/**
	 * Um tick de luta contra o alvo: olha, chega perto se precisar e bate quando a
	 * recarga encheu. Devolve true se bateu.
	 */
	public static boolean fight(ServerPlayer me, LivingEntity target, Controls controls) {
		equipWeapon(me);
		controls.lookAt(target.getEyePosition().add(0, -0.3, 0));
		double dist = me.distanceTo(target);
		if (dist > REACH) {
			if (safeAhead(me, target)) {
				controls.forward(1);
				controls.jump(me.horizontalCollision && me.onGround());
			}
			return false;
		}
		if (dist < 1.2) {
			controls.forward(-0.5F); // um passo para trás para não ficar dentro do bicho
		}
		boolean facing = dev.vry.waifuscam.control.Controls.aimed(me, target.getEyePosition().add(0, -0.3, 0), 30);
		if (facing && me.getAttackStrengthScale(0.5F) >= 0.95F && me.isWithinEntityInteractionRange(target, 0)) {
			me.attack(target);
			me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			me.resetAttackStrengthTicker();
			me.resetLastActionTime();
			return true;
		}
		return false;
	}

	/** Dá para dar um passo na direção do alvo sem cair num buraco fundo ou na lava? */
	static boolean safeAhead(ServerPlayer me, Entity target) {
		Vec3 dir = target.position().subtract(me.position()).multiply(1, 0, 1);
		if (dir.lengthSqr() < 1.0E-4) {
			return true;
		}
		Vec3 step = me.position().add(dir.normalize().scale(0.9));
		net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(step);
		var level = me.level();
		for (int down = 0; down <= 3; down++) {
			net.minecraft.core.BlockPos p = feet.below(down);
			if (!level.getFluidState(p).isEmpty() && level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) {
				return false;
			}
			if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
				return true; // chão a no máximo 3 blocos
			}
		}
		return false;
	}

	/** Corre na direção oposta à ameaça. */
	public static void flee(ServerPlayer me, Entity from, Controls controls) {
		Vec3 away = me.position().subtract(from.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1.0E-4) {
			away = new Vec3(1, 0, 0);
		}
		Vec3 target = me.position().add(away.normalize().scale(6)).add(0, me.getEyeHeight(), 0);
		controls.lookAt(target);
		controls.forward(1);
		controls.sprint(true);
		controls.jump(me.horizontalCollision && me.onGround());
	}
}

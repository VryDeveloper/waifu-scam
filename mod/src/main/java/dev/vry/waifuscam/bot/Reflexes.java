package dev.vry.waifuscam.bot;

import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.hands.Archery;
import dev.vry.waifuscam.hands.Armor;
import dev.vry.waifuscam.hands.Combat;
import dev.vry.waifuscam.hands.Food;
import dev.vry.waifuscam.skill.EatSkill;
import dev.vry.waifuscam.skill.Skill;
import dev.vry.waifuscam.skill.SkillContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Reflexos: o que ela faz sem pensar, a cada tick, antes da habilidade atual.
 * Fugir de creeper, revidar quem bate nela, comer quando a fome aperta, subir
 * para respirar. Não esperam a IA: a IA só fica sabendo depois (eventos).
 *
 * Quando um reflexo assume, a habilidade atual fica pausada (não termina) e
 * continua sozinha quando o perigo passa.
 */
final class Reflexes {
	/** Comer quando a fome chegar aqui (abaixo de 18 a vida não regenera; em 6 não corre). */
	private static final int EAT_BELOW = 15;
	private static final double DANGER_RADIUS = 12;
	private static final double ENGAGE_RADIUS = 6;
	private static final int EVENT_COOLDOWN = 20 * 20;

	private final BiConsumer<String, String> events;
	private final Archery archery = new Archery();
	private Skill interrupt;
	private int eatCooldown;
	private int lastDangerEvent = -EVENT_COOLDOWN;
	private int creeperBackoff; // ticks correndo para trás depois de bater no creeper
	private String lastDangerType;
	private boolean enabled = true;

	Reflexes(BiConsumer<String, String> events) {
		this.events = events;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/** O que ela está fazendo por reflexo agora (para o state), ou null. */
	String doing() {
		return interrupt != null ? interrupt.describe() : null;
	}

	void reset() {
		interrupt = null;
	}

	/** true = o reflexo assumiu neste tick e a habilidade não deve rodar. */
	boolean before(SkillContext ctx, int tick) {
		if (!enabled) {
			return false;
		}
		ServerPlayer me = ctx.player();
		if (me.isSleeping()) {
			return false; // dormindo: o jogo acorda ela se algo acontecer
		}
		Controls c = ctx.controls();
		Optional<LivingEntity> threat = Combat.nearestHostile(me, DANGER_RADIUS);

		if (threat.isPresent()) {
			LivingEntity t = threat.get();
			double dist = t.distanceTo(me);
			announce(t, tick);
			if (t instanceof Creeper creeper && dist < 8) {
				// Creeper: bate e recua, bate e recua. Quando ele começa a inchar, corre até sair do alcance.
				interrupt = null;
				boolean canHit = Combat.hasWeapon(me) && me.getHealth() > 8;
				if (creeper.getSwellDir() > 0 || creeperBackoff > 0 || !canHit) {
					if (creeperBackoff > 0) {
						creeperBackoff--;
					}
					archery.cancel(me);
		Combat.lowerShield(me);
		creeperBackoff = 0;
					Combat.flee(me, t, c);
					return true;
				}
				if (Archery.canShoot(me) && dist > Archery.MIN_RANGE) {
					archery.tick(me, t, c);
					return true;
				}
				archery.cancel(me);
				if (Combat.fight(me, t, c)) {
					creeperBackoff = 14; // bateu: o creeper é empurrado e ela se afasta antes de ele explodir
				}
				return true;
			}
			if (me.getHealth() <= 6 && dist < ENGAGE_RADIUS) {
				interrupt = null;
				archery.cancel(me);
				Combat.flee(me, t, c);
				return true;
			}
			boolean wantsUs = targetsUs(t, me);
			if (dist <= Combat.REACH + 0.6 || (wantsUs && dist <= ENGAGE_RADIUS && !(t instanceof Creeper))) {
				interrupt = null;
				archery.cancel(me);
				Combat.fight(me, t, c);
				return true;
			}
		}

		// De longe, com arco: quem vem atrás dela ou de quem ela acompanha, e creeper chegando.
		if (Archery.canShoot(me)) {
			Optional<LivingEntity> far = Combat.nearestHostile(me, Archery.MAX_RANGE);
			if (far.isPresent() && far.get().distanceTo(me) > Archery.MIN_RANGE && worthShooting(far.get(), me)) {
				interrupt = null;
				announce(far.get(), tick);
				archery.tick(me, far.get(), c);
				return true;
			}
		}
		archery.cancel(me);

		if (interrupt != null) {
			Skill.Status s = interrupt.tick(ctx);
			if (s != Skill.Status.RUNNING) {
				if (s == Skill.Status.FAILED) {
					eatCooldown = 20 * 30;
				}
				interrupt = null;
			}
			return true;
		}

		if (tick % 100 == 0 && threat.isEmpty() && !me.isUsingItem()) {
			Armor.equipBest(me); // vestiu armadura nova que pegou
		}
		if (eatCooldown > 0) {
			eatCooldown--;
		} else if (!me.isUsingItem() && hungry(me) && Food.hasFood(me) && threat.isEmpty()) {
			interrupt = new EatSkill(false);
			return true;
		}
		return false;
	}

	/** Depois da habilidade: o que vale por cima de qualquer coisa (respirar). */
	void after(SkillContext ctx) {
		if (!enabled) {
			return;
		}
		ServerPlayer me = ctx.player();
		if (me.isUnderWater() && me.getAirSupply() < me.getMaxAirSupply() / 2) {
			ctx.controls().jump(true);
		}
		if (me.isInLava()) {
			ctx.controls().jump(true).forward(1).sprint(true);
		}
	}

	/** Está mirando nela ou num player de verdade perto dela (que ela acompanha). */
	private static boolean targetsUs(LivingEntity t, ServerPlayer me) {
		if (!(t instanceof net.minecraft.world.entity.Mob mob) || mob.getTarget() == null) {
			return false;
		}
		LivingEntity victim = mob.getTarget();
		return victim == me || (victim instanceof ServerPlayer p && !(p instanceof CompanionPlayer) && p.distanceTo(me) < 16);
	}

	private static boolean worthShooting(LivingEntity t, ServerPlayer me) {
		return targetsUs(t, me) || (t instanceof Creeper && t.distanceTo(me) < 14);
	}

	private static boolean hungry(ServerPlayer me) {
		int food = me.getFoodData().getFoodLevel();
		return food < EAT_BELOW || (me.getHealth() < me.getMaxHealth() - 6 && food < 18);
	}

	private void announce(LivingEntity t, int tick) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).getPath();
		if (tick - lastDangerEvent >= EVENT_COOLDOWN || !type.equals(lastDangerType)) {
			lastDangerEvent = tick;
			lastDangerType = type;
			events.accept("danger", type);
		}
	}
}

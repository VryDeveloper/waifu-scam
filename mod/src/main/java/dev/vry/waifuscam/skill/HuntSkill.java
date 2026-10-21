package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.hands.Combat;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Caçar ou lutar: acha o bicho mais perto do tipo pedido, chega perto, bate até
 * morrer e pega o que caiu. Com `items`, para quando tiver juntado mais `count`
 * desses itens (carne, lã); sem, para depois de `count` abates.
 *
 * Nunca ataca player, bicho com nome (pet de alguém) nem filhote.
 */
public final class HuntSkill implements Skill {
	private static final double SEARCH_RADIUS = 32;
	private static final int TIMEOUT_TICKS = 20 * 60 * 3;

	private final Predicate<LivingEntity> wanted;
	private final Set<String> items;
	private final int count;
	private final String label;

	private int goal = -1;
	private int kills;
	private LivingEntity target;
	private Skill walk;
	private int ticks;
	private int pickupTicks = -1;
	private String failure;

	public HuntSkill(Set<String> entityTypes, Set<String> items, int count, String label) {
		String color = woolColor(items);
		Predicate<LivingEntity> type = e -> entityTypes.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		// Lã: só ovelha da cor certa e que ainda tem lã.
		this.wanted = color == null ? type : type.and(e -> e instanceof Sheep s && !s.isSheared()
				&& s.getColor().getSerializedName().equals(color));
		this.items = items;
		this.count = Math.max(1, count);
		this.label = label;
	}

	/** Atacar um bicho específico (o que o player está atacando, por exemplo). */
	public static HuntSkill target(LivingEntity target, String label) {
		java.util.UUID id = target.getUUID();
		return new HuntSkill(e -> e.getUUID().equals(id), 1, label);
	}

	/** Atacar bichos destes tipos (iron_golem, zombie...), count abates. */
	public static HuntSkill types(Set<String> entityTypes, int count, String label) {
		return new HuntSkill(e -> entityTypes.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()), count, label);
	}

	/** Lutar contra qualquer bicho hostil por perto (count abates). */
	public static HuntSkill hostiles(ServerPlayer me, int count) {
		return new HuntSkill(e -> Combat.isHostile(e, me), count, "os monstros");
	}

	private HuntSkill(Predicate<LivingEntity> wanted, int count, String label) {
		this.wanted = wanted;
		this.items = Set.of();
		this.count = Math.max(1, count);
		this.label = label;
	}

	@Override
	public String name() {
		return "hunt";
	}

	@Override
	public String describe() {
		return "caçando " + label;
	}

	/** "white" se o pedido é só lã branca; null se não é lã de uma cor. */
	private static String woolColor(Set<String> items) {
		if (items.size() != 1) {
			return null;
		}
		String id = items.iterator().next().replace("minecraft:", "");
		return id.endsWith("_wool") ? id.substring(0, id.length() - "_wool".length()) : null;
	}

	/** Com tesoura, tosquia em vez de matar (3 lãs e a ovelha fica bem). */
	private boolean shear(ServerPlayer me, LivingEntity target, SkillContext ctx) {
		if (!(target instanceof Sheep sheep) || !sheep.readyForShearing()) {
			return false;
		}
		var inv = me.getInventory();
		int slot = Stacks.find(inv, s -> s.is(Items.SHEARS));
		if (slot < 0) {
			return false;
		}
		Stacks.hold(inv, slot);
		ctx.controls().lookAt(sheep.getEyePosition());
		me.interactOn(sheep, InteractionHand.MAIN_HAND, sheep.getBoundingBox().getCenter().subtract(sheep.position()));
		me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		pickupTicks = 0;
		this.target = null;
		return true;
	}

	private int have(SkillContext ctx) {
		return Stacks.count(ctx.player().getInventory(), s -> items.contains(Stacks.id(s)));
	}

	private boolean done(SkillContext ctx) {
		return items.isEmpty() ? kills >= count : have(ctx) >= goal;
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (goal < 0) {
			goal = have(ctx) + count;
		}
		if (++ticks > TIMEOUT_TICKS) {
			return fail("não achei " + label + " para caçar");
		}

		// Depois de um abate: pega os drops antes de seguir.
		if (pickupTicks >= 0) {
			if (++pickupTicks > 20 * 6 || pickup(ctx)) {
				pickupTicks = -1;
				walk = null;
				if (done(ctx)) {
					return Status.SUCCEEDED;
				}
			}
			return Status.RUNNING;
		}
		if (done(ctx)) {
			return Status.SUCCEEDED;
		}

		if (target != null && !target.isAlive()) {
			kills++;
			pickupTicks = 0;
			target = null;
			return Status.RUNNING;
		}
		if (target == null || target.distanceTo(me) > SEARCH_RADIUS || ticks % 100 == 0) {
			Optional<LivingEntity> found = find(me);
			if (found.isEmpty()) {
				return target == null ? fail("não tem " + label + " por perto") : Status.RUNNING;
			}
			target = found.get();
			walk = null;
		}

		double dist = target.distanceTo(me);
		if (dist <= 3 && !items.isEmpty() && shear(me, target, ctx)) {
			return Status.RUNNING;
		}
		if (dist <= 4 && me.hasLineOfSight(target)) {
			Combat.fight(me, target, ctx.controls());
			return Status.RUNNING;
		}
		if (walk == null || ticks % 20 == 0) {
			walk = new GotoSkill(LevelTerrain.of(target.blockPosition()), 2.0, label);
		}
		if (walk.tick(ctx) == Status.FAILED) {
			target = null; // esse não dá para alcançar: tenta outro
			walk = null;
		}
		return Status.RUNNING;
	}

	private Optional<LivingEntity> find(ServerPlayer me) {
		AABB box = me.getBoundingBox().inflate(SEARCH_RADIUS, 12, SEARCH_RADIUS);
		return me.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && wanted.test(e)
						&& !(e instanceof Player) && !e.hasCustomName() && !e.isBaby())
				.stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
	}

	/** true quando não sobrou drop por perto. */
	private boolean pickup(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		Optional<ItemEntity> drop = me.level().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(6),
						e -> e.isAlive() && !e.getItem().isEmpty())
				.stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
		if (drop.isEmpty()) {
			return pickupTicks > 10;
		}
		if (walk == null || pickupTicks % 20 == 0) {
			walk = new GotoSkill(LevelTerrain.of(drop.get().blockPosition()), 0.6, "item");
		}
		return walk.tick(ctx) == Status.FAILED;
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

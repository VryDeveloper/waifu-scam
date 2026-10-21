package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cuidar dos bichos como um player:
 * - breed: dar a comida certa para dois adultos (vaca/ovelha trigo, porco
 *   cenoura, galinha semente...) e nasce um filhote;
 * - tame: domesticar (lobo com osso, gato com peixe, papagaio com semente,
 *   cavalo montando até ele aceitar) e deixar o bicho como seu, do player que pediu.
 * Sem a comida, ela arruma antes.
 */
public final class AnimalSkill implements Skill {
	public enum Mode { BREED, TAME }

	private static final double RADIUS = 20;
	private static final int TIMEOUT_TICKS = 20 * 90;
	/** Comida para arrumar, se ela não tiver nada que o bicho aceite. */
	private static final Map<String, String> FOOD = Map.ofEntries(
			Map.entry("cow", "minecraft:wheat"), Map.entry("sheep", "minecraft:wheat"), Map.entry("goat", "minecraft:wheat"),
			Map.entry("mooshroom", "minecraft:wheat"), Map.entry("pig", "minecraft:carrot"),
			Map.entry("chicken", "minecraft:wheat_seeds"), Map.entry("rabbit", "minecraft:carrot"),
			Map.entry("wolf", "minecraft:bone"), Map.entry("cat", "minecraft:cod"), Map.entry("ocelot", "minecraft:cod"),
			Map.entry("parrot", "minecraft:wheat_seeds"), Map.entry("horse", "minecraft:golden_carrot"),
			Map.entry("llama", "minecraft:hay_block"), Map.entry("fox", "minecraft:sweet_berries"),
			Map.entry("turtle", "minecraft:seagrass"), Map.entry("bee", "minecraft:dandelion"));

	private final Mode mode;
	private final String type; // "cow", "wolf"...
	private final String forPlayer;
	private final Set<UUID> fed = new HashSet<>();
	private Animal target;
	private Skill child;
	private Skill walk;
	private int ticks;
	private int wait;
	private int tries;
	private boolean gotFood;
	private String failure;

	public AnimalSkill(Mode mode, String type, String forPlayer) {
		this.mode = mode;
		this.type = type.replace("minecraft:", "").toLowerCase();
		this.forPlayer = forPlayer;
	}

	@Override
	public String name() {
		return mode == Mode.BREED ? "breed" : "tame";
	}

	@Override
	public String describe() {
		return (mode == Mode.BREED ? "alimentando os " : "domesticando ") + type;
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (++ticks > TIMEOUT_TICKS) {
			return fail("demorei demais com os " + type);
		}
		if (child != null) {
			Status s = child.tick(ctx);
			if (s == Status.RUNNING) {
				return Status.RUNNING;
			}
			child = null;
			if (s == Status.FAILED) {
				return fail("não consegui comida para " + type);
			}
		}
		if (wait-- > 0) {
			return Status.RUNNING;
		}
		if (mode == Mode.BREED && fed.size() >= 2) {
			return Status.SUCCEEDED; // dois apaixonados: o filhote vem
		}
		if (target == null || !target.isAlive() || done(target)) {
			if (target != null && mode == Mode.TAME && done(target)) {
				giveTo(target, ctx);
				return Status.SUCCEEDED;
			}
			target = next(me);
			tries = 0;
			if (target == null) {
				return fed.isEmpty() ? fail("não achei " + type + " " + (mode == Mode.BREED ? "adulto" : "selvagem") + " por perto")
						: Status.SUCCEEDED;
			}
		}
		// Comida certa na mão
		int slot = foodSlot(me, target);
		if (slot < 0 && !(target instanceof AbstractHorse && mode == Mode.TAME)) {
			String food = FOOD.get(type);
			if (food == null || gotFood) {
				return fail("não sei o que " + type + " come");
			}
			gotFood = true;
			child = new ObtainSkill(Choice.of(food), mode == Mode.BREED ? 2 : 6, food.replace("minecraft:", ""));
			return Status.RUNNING;
		}
		if (me.distanceTo(target) > 2.5) {
			if (walk == null || ticks % 20 == 0) {
				walk = new GotoSkill(LevelTerrain.of(target.blockPosition()), 2.0, type);
			}
			if (walk.tick(ctx) == Status.FAILED) {
				fed.add(target.getUUID()); // não dá para chegar nesse: tenta outro
				target = null;
				walk = null;
			}
			return Status.RUNNING;
		}
		walk = null;
		ctx.controls().lookAt(target.getEyePosition());
		if (slot >= 0) {
			Stacks.hold(me.getInventory(), slot);
		} else {
			// cavalo: monta com a mão vazia até ele aceitar
			for (int i = 0; i < 9; i++) {
				if (me.getInventory().getItem(i).isEmpty()) {
					me.getInventory().setSelectedSlot(i);
					break;
				}
			}
		}
		me.interactOn(target, InteractionHand.MAIN_HAND, target.getBoundingBox().getCenter().subtract(target.position()));
		me.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		wait = Human.pause();
		if (mode == Mode.BREED && target.isInLove()) {
			fed.add(target.getUUID());
			target = null;
		} else if (++tries > 12) {
			fed.add(target.getUUID()); // esse não quer: tenta outro
			target = null;
		}
		return Status.RUNNING;
	}

	private boolean done(Animal a) {
		if (mode == Mode.BREED) {
			return a.isInLove();
		}
		return a instanceof TamableAnimal t ? t.isTame() : a instanceof AbstractHorse h && h.isTamed();
	}

	private Animal next(ServerPlayer me) {
		List<Animal> all = me.level().getEntitiesOfClass(Animal.class, me.getBoundingBox().inflate(RADIUS), a -> a.isAlive()
				&& BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath().equals(type) && !fed.contains(a.getUUID()));
		return all.stream()
				.filter(a -> mode == Mode.BREED ? !a.isBaby() && a.canFallInLove() : !done(a))
				.min(Comparator.comparingDouble(a -> a.distanceToSqr(me)))
				.orElse(null);
	}

	private static int foodSlot(ServerPlayer me, Animal a) {
		var inv = me.getInventory();
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && a.isFood(s)) {
				return i;
			}
		}
		return -1;
	}

	/** Domesticado por ela: passa para o player que pediu. */
	private void giveTo(Animal a, SkillContext ctx) {
		Player owner = forPlayer != null ? ctx.server().getPlayerList().getPlayerByName(forPlayer) : null;
		if (owner == null) {
			return;
		}
		if (a instanceof TamableAnimal t) {
			t.tame(owner);
		} else if (a instanceof AbstractHorse h) {
			h.tameWithName(owner);
		}
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return true; // dar comida é ficar parada do lado do bicho
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

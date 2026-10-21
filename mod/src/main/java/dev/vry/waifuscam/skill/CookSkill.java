package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Knowledge.SmeltRecipe;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.Food;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Faz comida": olha o inventário, acha o que é comida crua que a fornalha
 * melhora (carne, peixe, batata, alga) e assa tudo. Sem nada cru, arruma comida
 * do jeito mais fácil (caçar e assar, ou pão), como um player faz.
 */
public final class CookSkill implements Skill {
	/** Comidas prontas que valem a pena, para quando não tem nada cru. */
	private static final List<String> MEALS = List.of("minecraft:cooked_beef", "minecraft:cooked_porkchop",
			"minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:bread", "minecraft:baked_potato",
			"minecraft:cooked_salmon", "minecraft:cooked_cod");

	private final List<Skill> queue = new ArrayList<>();
	private Skill current;
	private boolean planned;
	private int done;
	private String failure;
	private String label = "comida";

	@Override
	public String name() {
		return "cook";
	}

	@Override
	public String describe() {
		return current != null ? "cozinhando (" + current.describe() + ")" : "cozinhando";
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (!planned) {
			planned = true;
			plan(ctx);
		}
		if (current == null) {
			if (queue.isEmpty()) {
				return done > 0 ? Status.SUCCEEDED : fail(failure != null ? failure : "não consegui fazer " + label);
			}
			current = queue.remove(0);
		}
		Status s = current.tick(ctx);
		if (s == Status.RUNNING) {
			return Status.RUNNING;
		}
		if (s == Status.SUCCEEDED) {
			done++;
		} else {
			failure = current.failureReason();
		}
		current = null;
		return Status.RUNNING;
	}

	private void plan(SkillContext ctx) {
		GameKnowledge k = GameKnowledge.of(ctx.server());
		var inv = ctx.player().getInventory();
		// Comida crua que assada fica comida melhor: porkchop → cooked_porkchop.
		Map<String, Integer> raw = new LinkedHashMap<>();
		Map<String, String> outputOf = new LinkedHashMap<>();
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			String id = Stacks.id(s);
			for (SmeltRecipe r : k.smeltsFrom(id)) {
				ItemStack out = new ItemStack(Stacks.item(r.output()));
				if (Food.edible(out) && !Food.bad(out)) {
					raw.merge(id, s.getCount(), Integer::sum);
					outputOf.put(id, r.output());
					break;
				}
			}
		}
		for (var e : raw.entrySet()) {
			String out = outputOf.get(e.getKey());
			Choice c = Choice.of(out);
			queue.add(new ObtainSkill(c, Stacks.count(inv, c.items()) + e.getValue(), Names.pretty(out)));
		}
		if (queue.isEmpty()) {
			// Nada cru: arruma umas 3 comidas boas do jeito mais barato.
			Choice meals = new Choice(MEALS);
			queue.add(new ObtainSkill(meals, Stacks.count(inv, MEALS) + 3, "comida"));
		}
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return current != null && current.patient();
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

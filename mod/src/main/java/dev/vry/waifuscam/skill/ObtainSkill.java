package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.craft.Planner;
import dev.vry.waifuscam.craft.Planner.Craft;
import dev.vry.waifuscam.craft.Planner.Gather;
import dev.vry.waifuscam.craft.Planner.Plan;
import dev.vry.waifuscam.craft.Planner.Smelt;
import dev.vry.waifuscam.craft.Planner.Step;
import dev.vry.waifuscam.hands.Stacks;

import java.util.Map;

/**
 * Conseguir um item do zero: planeja (minerar, craftar, fundir), executa o
 * primeiro passo e planeja de novo com o inventário novo, até ter o que queria.
 *
 * "Faz uma picareta de pedra" vira: pegar tronco, tábuas, gravetos, bancada,
 * picareta de madeira, cavar até a pedra, picareta de pedra.
 */
public final class ObtainSkill implements Skill {
	private static final int MAX_STEPS = 80;
	private static final int MAX_FAILURES = 3;

	private final Choice wanted;
	private final int count;
	private final String label;

	private Skill child;
	private String childLabel;
	private int steps;
	private int failures;
	private int pause;
	private String failure;

	public ObtainSkill(Choice wanted, int count, String label) {
		this.wanted = wanted;
		this.count = Math.max(1, count);
		this.label = label;
	}

	@Override
	public String name() {
		return "obtain";
	}

	@Override
	public String describe() {
		String base = "conseguindo " + (count > 1 ? count + " " : "") + label;
		return child == null ? base : base + " (" + child.describe() + ")";
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (child != null) {
			Status s = child.tick(ctx);
			if (s == Status.RUNNING) {
				return Status.RUNNING;
			}
			if (s == Status.FAILED) {
				String why = child.failureReason();
				child = null;
				if (++failures >= MAX_FAILURES) {
					return fail(why != null ? why : "não deu certo " + childLabel);
				}
			} else {
				child = null;
			}
			pause = dev.vry.waifuscam.control.Human.pause(); // um respiro entre um passo e outro
		}
		if (pause-- > 0) {
			return Status.RUNNING;
		}
		if (++steps > MAX_STEPS) {
			return fail("isso está levando passos demais");
		}

		GameKnowledge knowledge = GameKnowledge.of(ctx.server());
		Map<String, Integer> inv = Stacks.counts(ctx.player().getInventory());
		Plan plan = new Planner(knowledge).plan(wanted, count, inv, Queries.world(ctx.player()));
		if (plan.done()) {
			return Status.SUCCEEDED;
		}
		if (!plan.possible()) {
			return fail(plan.failure());
		}
		child = toSkill(plan, plan.next(), knowledge);
		childLabel = child.describe();
		return Status.RUNNING;
	}

	private static Skill toSkill(Plan plan, Step step, GameKnowledge knowledge) {
		return switch (step) {
			case Gather g -> {
				// Junta as coletas da mesma fonte numa ida só (3 troncos de uma vez, não 1+1+1).
				int total = 0;
				for (Step s : plan.steps()) {
					if (s instanceof Gather other && other.source().equals(g.source())) {
						total += other.count();
					}
				}
				yield g.source().hunt()
						? new HuntSkill(g.source().blocks(), g.source().items(), total, Names.pretty(g.item()))
						: new CollectSkill(g.source(), total);
			}
			case Craft c -> new CraftSkill(c.recipe(), c.times(), knowledge);
			case Smelt m -> new SmeltSkill(m.recipe(), m.times(), knowledge);
		};
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return child != null && child.patient();
	}

	@Override
	public String failureReason() {
		return failure;
	}

	public static String labelFor(String raw) {
		return Names.pretty(Stacks.normalize(raw));
	}
}

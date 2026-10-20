package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.PathFollower;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Path;
import dev.vry.waifuscam.pathing.PathFinder;
import dev.vry.waifuscam.pathing.Pos;

/** Ir até um ponto do mundo e parar lá. */
public final class GotoSkill implements Skill {
	private static final int MAX_REPLANS = 6;
	private static final int TIMEOUT_TICKS = 20 * 90;

	private final Pos target;
	private final double radius;
	private final String label;

	private PathFollower follower;
	private Pos lastPartialEnd;
	private int replans;
	private int ticks;
	private String failure;

	public GotoSkill(Pos target, double radius, String label) {
		this.target = target;
		this.radius = radius;
		this.label = label;
	}

	@Override
	public String name() {
		return "goto";
	}

	@Override
	public String describe() {
		return "indo até " + label;
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (++ticks > TIMEOUT_TICKS) {
			return fail("demorei demais e desisti");
		}
		if (follower == null) {
			LevelTerrain terrain = new LevelTerrain(ctx.level());
			Path path = new PathFinder(terrain).find(ctx.feet(terrain), target, radius);
			if (path.isEmpty()) {
				return path.reachesGoal() ? Status.SUCCEEDED : fail("não achei caminho até lá");
			}
			if (!path.reachesGoal()) {
				// Caminho parcial: anda até onde dá e planeja de novo de lá.
				if (path.end().equals(lastPartialEnd)) {
					return fail("não consigo chegar até lá daqui");
				}
				lastPartialEnd = path.end();
			}
			follower = new PathFollower(path);
		}

		switch (follower.tick(ctx.player(), ctx.controls(), true)) {
			case ARRIVED -> {
				if (follower.path().reachesGoal()) {
					return Status.SUCCEEDED;
				}
				return replan("não consigo chegar até lá daqui");
			}
			case STUCK -> {
				return replan("fiquei presa no caminho");
			}
			default -> {
				return Status.RUNNING;
			}
		}
	}

	private Status replan(String reasonIfGivingUp) {
		follower = null;
		return ++replans > MAX_REPLANS ? fail(reasonIfGivingUp) : Status.RUNNING;
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

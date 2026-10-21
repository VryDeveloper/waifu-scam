package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.PathFollower;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Path;
import dev.vry.waifuscam.pathing.PathFinder;
import dev.vry.waifuscam.pathing.Pos;

/** Ir até um ponto do mundo e parar lá. */
public final class GotoSkill implements Skill {
	private static final int MAX_REPLANS = 6;

	private final Pos target;
	private final double radius;
	private final String label;

	private PathFollower follower;
	private Pos lastPartialEnd;
	private int replans;
	private int ticks;
	private int timeout = -1;
	private int stalls;
	private double bestPartial = Double.MAX_VALUE;
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
		if (timeout < 0) {
			// Mais longe, mais tempo: 1 min + 1,5 s por bloco (no máximo 10 min).
			double far = ctx.player().position().distanceTo(new net.minecraft.world.phys.Vec3(target.x(), target.y(), target.z()));
			timeout = (int) Math.min(20 * 600, 20 * 60 + far * 30);
		}
		if (++ticks > timeout) {
			return fail("demorei demais e desisti");
		}
		if (follower == null) {
			LevelTerrain terrain = new LevelTerrain(ctx.level());
			Path path = new PathFinder(terrain).find(ctx.feet(terrain), target, radius);
			if (path.isEmpty()) {
				return path.reachesGoal() ? Status.SUCCEEDED : fail("não achei caminho até lá");
			}
			if (!path.reachesGoal()) {
				// Caminho parcial (longe demais para planejar de uma vez, ou bloqueado):
				// anda até o ponto mais perto e planeja de novo de lá, enquanto estiver chegando mais perto.
				double left = path.end().distanceTo(target);
				if (path.end().equals(lastPartialEnd) || left > bestPartial - 1.5) {
					if (++stalls > 2) {
						return fail("não consigo chegar até lá daqui");
					}
				} else {
					stalls = 0;
				}
				bestPartial = Math.min(bestPartial, left);
				lastPartialEnd = path.end();
			}
			follower = new PathFollower(path);
		}

		double remaining = ctx.feet(new LevelTerrain(ctx.level())).distanceTo(target);
		switch (follower.tick(ctx.player(), ctx.controls(), remaining > 10)) {
			case ARRIVED -> {
				if (follower.path().reachesGoal()) {
					return Status.SUCCEEDED;
				}
				follower = null; // fim de um trecho: planeja o próximo
				return Status.RUNNING;
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

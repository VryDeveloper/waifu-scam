package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.PathFollower;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Path;
import dev.vry.waifuscam.pathing.PathFinder;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Seguir um player até mandarem parar. Fica a uma distância confortável, olhando
 * para ele quando está parada, e corre quando ele se afasta.
 */
public final class FollowSkill implements Skill {
	private static final double TOO_FAR = 96;
	private static final int REPLAN_INTERVAL = 10;
	/** Replanejamentos seguidos em que ela quase não saiu do lugar antes de desistir (~20 s). */
	private static final int MAX_FUTILE_REPLANS = 40;

	private final UUID targetId;
	private final String targetName;
	private final double distance;

	private PathFollower follower;
	private Pos plannedGoal;
	private int sinceReplan = REPLAN_INTERVAL;
	private int futileReplans;
	private Vec3 positionAtLastReplan;
	private String failure;
	private net.minecraft.core.BlockPos lastTorch;
	private int ticks;

	public FollowSkill(ServerPlayer target, double distance) {
		this.targetId = target.getUUID();
		this.targetName = target.getGameProfile().name();
		this.distance = distance;
	}

	@Override
	public String name() {
		return "follow";
	}

	@Override
	public String describe() {
		return "seguindo " + targetName;
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer target = ctx.server().getPlayerList().getPlayer(targetId);
		if (target == null) {
			return fail(targetName + " saiu do jogo");
		}
		if (target.level() != ctx.level()) {
			return fail(targetName + " foi para outra dimensão");
		}
		double dist = ctx.player().distanceTo(target);
		if (dist > TOO_FAR) {
			return fail(targetName + " está longe demais");
		}
		if (++ticks % 20 == 0) {
			lightTheWay(ctx);
		}
		if (riding(ctx, target, dist)) {
			return Status.RUNNING;
		}

		if (dist <= distance) {
			// Perto: para e fica olhando para ele.
			follower = null;
			futileReplans = 0;
			positionAtLastReplan = null;
			ctx.controls().lookAt(target.getEyePosition());
			return Status.RUNNING;
		}

		LevelTerrain terrain = new LevelTerrain(ctx.level());
		Pos goal = SkillContext.feetOf(target, terrain);
		sinceReplan++;
		boolean targetMoved = plannedGoal == null || plannedGoal.distanceTo(goal) > 2;
		// Sem caminho (ou ele andou), planeja de novo, mas no máximo a cada REPLAN_INTERVAL ticks.
		if (sinceReplan >= REPLAN_INTERVAL && (follower == null || targetMoved)) {
			sinceReplan = 0;
			// Progresso = ela se mexeu desde o último plano (ele pode estar andando também).
			Vec3 here = ctx.player().position();
			if (positionAtLastReplan == null || here.distanceTo(positionAtLastReplan) > 1.0) {
				futileReplans = 0;
			} else if (++futileReplans > MAX_FUTILE_REPLANS) {
				return fail("não consigo chegar até " + targetName);
			}
			positionAtLastReplan = here;
			Path path = new PathFinder(terrain).find(ctx.feet(terrain), goal, distance);
			plannedGoal = goal;
			follower = path.isEmpty() ? null : new PathFollower(path);
		}

		if (follower == null) {
			ctx.controls().lookAt(target.getEyePosition());
			return Status.RUNNING;
		}
		PathFollower.Status status = follower.tick(ctx.player(), ctx.controls(), dist > 8);
		if (status != PathFollower.Status.WALKING) {
			follower = null; // chegou no fim do caminho ou travou: replaneja no próximo tick
			sinceReplan = REPLAN_INTERVAL;
		}
		return Status.RUNNING;
	}

	/**
	 * Montada ou ele montado: vai de carona no barco/camelo dele, conduz a
	 * montaria dela atrás dele, desce quando ele desce. true = já cuidou do tick.
	 */
	private boolean riding(SkillContext ctx, ServerPlayer target, double dist) {
		var me = ctx.player();
		var mine = me.getVehicle();
		var his = target.getVehicle();
		if (mine == null) {
			// Ele entrou num barco com lugar sobrando, aqui do lado: ela entra junto.
			if (his instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boat && dist < 5
					&& boat.getPassengers().size() < 2) {
				me.startRiding(boat);
				return me.isPassenger();
			}
			return false;
		}
		if (mine == his) {
			ctx.controls().lookAt(target.getEyePosition()); // de carona com ele
			return true;
		}
		if (dev.vry.waifuscam.control.Driver.drives(me, mine) && his != null || dev.vry.waifuscam.control.Driver.drives(me, mine)
				&& mine instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) {
			// Ela conduz a montaria dela atrás dele.
			if (dist > distance) {
				dev.vry.waifuscam.control.Driver.drive(me, mine, target.position(), distance);
			}
			ctx.controls().lookAt(target.getEyePosition());
			return true;
		}
		// Estava de carona e ele desceu: desce também.
		me.stopRiding();
		return false;
	}

	/**
	 * Acompanhando alguém numa caverna escura: deixa tocha no caminho (como um
	 * player faz para achar a volta e não nascer monstro em volta).
	 */
	private void lightTheWay(SkillContext ctx) {
		var me = ctx.player();
		var level = ctx.level();
		var feet = me.blockPosition();
		if (level.canSeeSky(feet) || level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) >= 5) {
			return;
		}
		if (lastTorch != null && lastTorch.distManhattan(feet) < 8) {
			return;
		}
		if (dev.vry.waifuscam.hands.Stacks.find(me.getInventory(), s -> s.is(net.minecraft.world.item.Items.TORCH)) < 0) {
			return;
		}
		var placed = dev.vry.waifuscam.hands.Placer.placeNear(me, net.minecraft.world.item.Items.TORCH);
		if (placed != null) {
			lastTorch = placed;
		}
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return true;
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

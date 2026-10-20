package dev.vry.waifuscam.control;

import dev.vry.waifuscam.pathing.Path;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Anda por um Path nó a nó, como um player faria: olha para o próximo bloco,
 * anda para frente, pula quando precisa subir ou bateu numa parede, nada
 * para cima na água.
 */
public final class PathFollower {
	public enum Status { WALKING, ARRIVED, STUCK }

	/** Perto o bastante do centro do bloco para considerar que chegou nele. */
	private static final double REACHED = 0.35;
	/** Ticks sem chegar mais perto do próximo nó para considerar que travou (2 s). */
	private static final int STUCK_TICKS = 40;

	private final Path path;
	private int index;
	private double bestDistance = Double.MAX_VALUE;
	private int ticksWithoutProgress;

	public PathFollower(Path path) {
		this.path = path;
	}

	public Path path() {
		return path;
	}

	public Status tick(ServerPlayer player, Controls controls, boolean sprint) {
		if (index >= path.nodes().size()) {
			return Status.ARRIVED;
		}
		Pos node = path.nodes().get(index);
		Vec3 target = new Vec3(node.x() + 0.5, node.y(), node.z() + 0.5);
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double dy = target.y - player.getY();

		boolean grounded = player.onGround() || player.isInWater();
		if (horizontal < REACHED && dy > -1.5 && dy < 0.6 && grounded) {
			index++;
			bestDistance = Double.MAX_VALUE;
			ticksWithoutProgress = 0;
			if (index >= path.nodes().size()) {
				return Status.ARRIVED;
			}
			return tick(player, controls, sprint);
		}

		// Travou? Conta o tempo sem chegar mais perto do nó atual.
		double distance = horizontal + Math.abs(dy);
		if (distance < bestDistance - 0.05) {
			bestDistance = distance;
			ticksWithoutProgress = 0;
		} else if (++ticksWithoutProgress >= STUCK_TICKS) {
			return Status.STUCK;
		}

		// Olha na altura dos olhos, para andar reto em vez de olhar para o chão.
		controls.lookAt(new Vec3(target.x, player.getEyeY() + Math.max(-1, Math.min(1, dy)) * 0.5, target.z));
		// Perto do nó, anda mais devagar para não passar do ponto.
		controls.forward((float) Math.max(0.35, Math.min(1.0, horizontal * 1.5)));

		boolean blocked = player.horizontalCollision && player.onGround();
		if (player.isInWater()) {
			controls.jump(dy > -0.3 || player.isUnderWater());
		} else {
			controls.jump(player.onGround() && (dy > 0.6 || blocked));
		}
		controls.sprint(sprint && horizontal > 2 && !player.isInWater());
		return Status.WALKING;
	}
}

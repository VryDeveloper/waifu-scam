package dev.vry.waifuscam.pathing;

/**
 * Posição de bloco onde ficam os pés da personagem. Independente do Minecraft
 * (BlockPos), para o pathfinding poder ser testado sem o jogo.
 */
public record Pos(int x, int y, int z) {
	public Pos offset(int dx, int dy, int dz) {
		return new Pos(x + dx, y + dy, z + dz);
	}

	public Pos above() {
		return offset(0, 1, 0);
	}

	public Pos below() {
		return offset(0, -1, 0);
	}

	public double distanceTo(Pos o) {
		return Math.sqrt(distanceSq(o));
	}

	public double distanceSq(Pos o) {
		double dx = x - o.x, dy = y - o.y, dz = z - o.z;
		return dx * dx + dy * dy + dz * dz;
	}

	public double horizontalDistanceTo(Pos o) {
		double dx = x - o.x, dz = z - o.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}

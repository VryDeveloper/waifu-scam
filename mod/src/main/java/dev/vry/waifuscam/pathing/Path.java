package dev.vry.waifuscam.pathing;

import java.util.List;

/**
 * Caminho planejado: lista de posições dos pés, da primeira depois do início até o fim.
 *
 * @param reachesGoal false quando o objetivo estava longe ou bloqueado e o caminho
 *                    leva só até o ponto mais próximo que deu para alcançar.
 */
public record Path(List<Pos> nodes, boolean reachesGoal) {
	public Pos end() {
		return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
	}

	public boolean isEmpty() {
		return nodes.isEmpty();
	}
}

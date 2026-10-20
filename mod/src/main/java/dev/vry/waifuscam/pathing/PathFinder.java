package dev.vry.waifuscam.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* sobre a grade de blocos, com os movimentos que um player faz a pé:
 * andar (inclusive na diagonal), subir 1 bloco pulando, descer até 3 blocos
 * e nadar. Não quebra nem coloca blocos — isso fica para a fase 4.
 *
 * Se o objetivo não puder ser alcançado dentro do limite de busca, devolve o
 * caminho até o ponto mais perto dele (Path.reachesGoal() == false).
 */
public final class PathFinder {
	public static final int DEFAULT_MAX_NODES = 6000;
	static final int MAX_DROP = 3;

	private static final int[][] DIRECTIONS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1},
	};
	private static final double DIAGONAL = Math.sqrt(2);

	private final Terrain terrain;
	private final int maxNodes;

	public PathFinder(Terrain terrain) {
		this(terrain, DEFAULT_MAX_NODES);
	}

	public PathFinder(Terrain terrain, int maxNodes) {
		this.terrain = terrain;
		this.maxNodes = maxNodes;
	}

	/** Dá para ficar de pé com os pés em pos? */
	public boolean canStand(Pos pos) {
		Pos head = pos.above();
		if (!terrain.isLoaded(pos) || !terrain.isPassable(pos) || !terrain.isPassable(head)) {
			return false;
		}
		if (terrain.isDangerous(pos) || terrain.isDangerous(head)) {
			return false;
		}
		if (terrain.isWater(pos)) {
			return true; // nadando
		}
		Pos floor = pos.below();
		return terrain.isSolid(floor) && !terrain.isDangerous(floor);
	}

	/**
	 * Procura um caminho de start até ficar a no máximo radius blocos de goal.
	 * Devolve um Path vazio se nem dá para sair do lugar.
	 */
	public Path find(Pos start, Pos goal, double radius) {
		double radiusSq = radius * radius;
		Map<Pos, Node> nodes = new HashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>();

		Node first = new Node(start, null, 0, heuristic(start, goal));
		nodes.put(start, first);
		open.add(new Entry(first, first.f()));
		Node best = first;

		while (!open.isEmpty() && nodes.size() < maxNodes) {
			Entry entry = open.poll();
			Node current = entry.node;
			if (current.closed || entry.f > current.f()) {
				continue; // entrada velha: o nó já foi melhorado depois
			}
			current.closed = true;

			if (current.pos.distanceSq(goal) <= radiusSq) {
				return new Path(trace(current), true);
			}
			if (current.h < best.h) {
				best = current;
			}

			for (Move move : neighbours(current.pos)) {
				double g = current.g + move.cost;
				Node next = nodes.get(move.to);
				if (next == null) {
					next = new Node(move.to, current, g, heuristic(move.to, goal));
					nodes.put(move.to, next);
					open.add(new Entry(next, next.f()));
				} else if (!next.closed && g < next.g) {
					next.parent = current;
					next.g = g;
					open.add(new Entry(next, next.f())); // a entrada antiga é ignorada ao sair
				}
			}
		}
		return new Path(best == first ? List.of() : trace(best), false);
	}

	List<Move> neighbours(Pos from) {
		List<Move> moves = new ArrayList<>(12);
		boolean inWater = terrain.isWater(from);

		for (int[] d : DIRECTIONS) {
			int dx = d[0], dz = d[1];
			boolean diagonal = dx != 0 && dz != 0;
			if (diagonal && !(bodyFits(from.offset(dx, 0, 0)) && bodyFits(from.offset(0, 0, dz)))) {
				continue; // não corta quina de bloco
			}
			double step = diagonal ? DIAGONAL : 1.0;

			Pos flat = from.offset(dx, 0, dz);
			if (canStand(flat)) {
				moves.add(new Move(flat, step * (terrain.isWater(flat) ? 2.0 : 1.0)));
				continue;
			}

			// Subir um bloco pulando: precisa de espaço para a cabeça durante o pulo.
			if (!diagonal) {
				Pos up = from.offset(dx, 1, dz);
				if (terrain.isPassable(from.above().above()) && canStand(up)) {
					moves.add(new Move(up, inWater ? 3.0 : 2.0));
					continue;
				}
			}

			// Descer: andar para fora da borda e cair até MAX_DROP blocos.
			if (bodyFits(flat)) {
				for (int k = 1; k <= MAX_DROP; k++) {
					Pos down = flat.offset(0, -k, 0);
					if (!terrain.isPassable(down) || terrain.isDangerous(down)) {
						break;
					}
					if (canStand(down)) {
						moves.add(new Move(down, step + 0.5 * k));
						break;
					}
				}
			}
		}

		// Nadar para cima ou para baixo.
		if (inWater && canStand(from.above())) {
			moves.add(new Move(from.above(), 1.5));
		}
		if (terrain.isWater(from.below()) && canStand(from.below())) {
			moves.add(new Move(from.below(), 1.5));
		}
		return moves;
	}

	private boolean bodyFits(Pos pos) {
		return terrain.isPassable(pos) && terrain.isPassable(pos.above())
				&& !terrain.isDangerous(pos) && !terrain.isDangerous(pos.above());
	}

	private static double heuristic(Pos from, Pos goal) {
		return from.distanceTo(goal);
	}

	private static List<Pos> trace(Node end) {
		List<Pos> out = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) {
			out.add(n.pos);
		}
		Collections.reverse(out);
		return out;
	}

	record Move(Pos to, double cost) {}

	/** Entrada imutável da fila: mudar g de um nó que já está na fila quebraria a ordenação. */
	private record Entry(Node node, double f) implements Comparable<Entry> {
		@Override
		public int compareTo(Entry o) {
			return Double.compare(f, o.f);
		}
	}

	private static final class Node {
		final Pos pos;
		final double h;
		Node parent;
		double g;
		boolean closed;

		Node(Pos pos, Node parent, double g, double h) {
			this.pos = pos;
			this.parent = parent;
			this.g = g;
			this.h = h;
		}

		double f() {
			return g + h;
		}
	}
}

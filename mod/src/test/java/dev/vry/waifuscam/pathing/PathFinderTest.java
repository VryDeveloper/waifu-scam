package dev.vry.waifuscam.pathing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFinderTest {

	/** Mundo de teste: chão de pedra em y=0 (pés em y=1), o resto é ar. */
	static final class Grid implements Terrain {
		enum Kind { AIR, SOLID, WATER, LAVA }

		final Map<Pos, Kind> blocks = new HashMap<>();
		int floorY = 0;
		int size = 40;

		Grid() {
		}

		Kind kind(Pos p) {
			Kind k = blocks.get(p);
			if (k != null) {
				return k;
			}
			return p.y() == floorY ? Kind.SOLID : Kind.AIR;
		}

		Grid set(int x, int y, int z, Kind k) {
			blocks.put(new Pos(x, y, z), k);
			return this;
		}

		Grid wall(int x, int z, int height) {
			for (int y = 1; y <= height; y++) {
				set(x, y, z, Kind.SOLID);
			}
			return this;
		}

		@Override
		public boolean isLoaded(Pos p) {
			return Math.abs(p.x()) <= size && Math.abs(p.z()) <= size;
		}

		@Override
		public boolean isSolid(Pos p) {
			return kind(p) == Kind.SOLID;
		}

		@Override
		public boolean isPassable(Pos p) {
			Kind k = kind(p);
			return k == Kind.AIR || k == Kind.WATER || k == Kind.LAVA;
		}

		@Override
		public boolean isWater(Pos p) {
			return kind(p) == Kind.WATER;
		}

		@Override
		public boolean isDangerous(Pos p) {
			return kind(p) == Kind.LAVA;
		}
	}

	static final Pos START = new Pos(0, 1, 0);

	@Test
	void walksStraightOnFlatGround() {
		Path path = new PathFinder(new Grid()).find(START, new Pos(5, 1, 0), 0.5);
		assertTrue(path.reachesGoal());
		assertEquals(new Pos(5, 1, 0), path.end());
		assertEquals(5, path.nodes().size());
	}

	@Test
	void usesDiagonals() {
		Path path = new PathFinder(new Grid()).find(START, new Pos(4, 1, 4), 0.5);
		assertTrue(path.reachesGoal());
		assertEquals(4, path.nodes().size());
	}

	@Test
	void goesAroundAWall() {
		Grid g = new Grid();
		for (int z = -3; z <= 3; z++) {
			g.wall(2, z, 3); // parede alta demais para pular
		}
		Path path = new PathFinder(g).find(START, new Pos(4, 1, 0), 0.5);
		assertTrue(path.reachesGoal());
		assertTrue(path.nodes().stream().noneMatch(p -> p.x() == 2 && Math.abs(p.z()) <= 3));
	}

	@Test
	void jumpsUpOneBlockButNotTwo() {
		Grid one = new Grid().wall(1, 0, 1).wall(2, 0, 1).wall(3, 0, 1);
		Path up = new PathFinder(one).find(START, new Pos(2, 2, 0), 0.5);
		assertTrue(up.reachesGoal());
		assertEquals(new Pos(2, 2, 0), up.end());

		// Degrau de 2 blocos numa "ilha" cercada de parede: não dá para chegar em cima.
		Grid two = new Grid();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				if (Math.abs(x) == 3 || Math.abs(z) == 3) {
					two.wall(x, z, 5);
				}
			}
		}
		two.wall(1, 0, 2);
		Path blocked = new PathFinder(two).find(START, new Pos(1, 3, 0), 0.5);
		assertFalse(blocked.reachesGoal());
	}

	@Test
	void dropsUpToThreeBlocks() {
		Grid g = new Grid();
		g.floorY = -10; // sem chão padrão
		for (int x = -1; x <= 1; x++) {
			g.set(x, 0, 0, Grid.Kind.SOLID); // plataforma
		}
		g.set(2, -3, 0, Grid.Kind.SOLID); // chão 3 blocos abaixo
		Path path = new PathFinder(g).find(START, new Pos(2, -2, 0), 0.5);
		assertTrue(path.reachesGoal());
		assertEquals(List.of(new Pos(1, 1, 0), new Pos(2, -2, 0)), path.nodes());
	}

	@Test
	void neverStepsIntoLava() {
		Grid g = new Grid();
		for (int z = -1; z <= 1; z++) {
			g.set(2, 0, z, Grid.Kind.LAVA); // poça de lava no chão
		}
		for (int z = -5; z <= 5; z++) {
			if (Math.abs(z) > 1) {
				g.wall(2, z, 3);
			}
		}
		Path path = new PathFinder(g).find(START, new Pos(4, 1, 0), 0.5);
		assertTrue(path.reachesGoal()); // dá a volta pela ponta da parede
		assertTrue(path.nodes().stream().noneMatch(p -> p.x() == 2 && Math.abs(p.z()) <= 1));
	}

	@Test
	void swimsAcrossWater() {
		Grid g = new Grid();
		for (int x = 1; x <= 3; x++) {
			for (int z = -5; z <= 5; z++) {
				g.set(x, 0, z, Grid.Kind.WATER);
				g.set(x, 1, z, Grid.Kind.WATER);
			}
		}
		Path path = new PathFinder(g).find(START, new Pos(5, 1, 0), 0.5);
		assertTrue(path.reachesGoal());
	}

	@Test
	void unreachableGoalReturnsClosestPartialPath() {
		Grid g = new Grid();
		g.size = 6; // mundo pequeno: o objetivo está fora da área carregada
		Path path = new PathFinder(g).find(START, new Pos(30, 1, 0), 0.5);
		assertFalse(path.reachesGoal());
		assertEquals(6, path.end().x());
	}

	@Test
	void alreadyThereIsEmptyPath() {
		Path path = new PathFinder(new Grid()).find(START, START, 1.5);
		assertTrue(path.reachesGoal());
		assertTrue(path.isEmpty());
	}

	@Test
	void doesNotCutCornersDiagonally() {
		Grid g = new Grid().wall(1, 0, 2).wall(0, 1, 2);
		Path path = new PathFinder(g).find(START, new Pos(1, 1, 1), 0.5);
		assertTrue(path.reachesGoal());
		assertTrue(path.nodes().size() > 1, "não pode ir direto na diagonal entre dois blocos");
	}
}

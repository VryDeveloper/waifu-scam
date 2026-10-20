package dev.vry.waifuscam.craft;

import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Knowledge.CraftRecipe;
import dev.vry.waifuscam.craft.Knowledge.SmeltRecipe;
import dev.vry.waifuscam.craft.Knowledge.Source;
import dev.vry.waifuscam.craft.Planner.Craft;
import dev.vry.waifuscam.craft.Planner.Gather;
import dev.vry.waifuscam.craft.Planner.Plan;
import dev.vry.waifuscam.craft.Planner.Smelt;
import dev.vry.waifuscam.craft.Planner.Step;
import dev.vry.waifuscam.craft.Planner.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

	/** Um pedaço do Minecraft de verdade: madeira, pedra, ferro. */
	static final class Book implements Knowledge {
		final Map<String, List<CraftRecipe>> crafts = new HashMap<>();
		final Map<String, List<SmeltRecipe>> smelts = new HashMap<>();
		final Map<String, List<Source>> sources = new HashMap<>();

		static final List<String> LOGS = List.of("oak_log", "birch_log");
		static final List<String> PLANKS = List.of("oak_planks", "birch_planks");
		static final List<String> PICKAXES = List.of("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe");

		Book() {
			Source logs = new Source(Set.copyOf(LOGS), Set.copyOf(LOGS), List.of());
			sources.put("oak_log", List.of(logs));
			sources.put("birch_log", List.of(logs));
			sources.put("cobblestone", List.of(new Source(Set.of("cobblestone"), Set.of("stone"), PICKAXES)));
			sources.put("raw_iron", List.of(new Source(Set.of("raw_iron"), Set.of("iron_ore"), PICKAXES.subList(1, 3))));
			sources.put("coal", List.of(new Source(Set.of("coal"), Set.of("coal_ore"), PICKAXES)));

			craft("oak_planks", 4, false, Choice.of("oak_log"));
			craft("birch_planks", 4, false, Choice.of("birch_log"));
			Choice planks = new Choice(PLANKS);
			Choice stick = Choice.of("stick");
			craft("stick", 4, false, planks, planks);
			craft("crafting_table", 1, false, planks, planks, planks, planks);
			craft("wooden_pickaxe", 1, true, planks, planks, planks, stick, stick);
			Choice cobble = Choice.of("cobblestone");
			craft("stone_pickaxe", 1, true, cobble, cobble, cobble, stick, stick);
			craft("furnace", 1, true, cobble, cobble, cobble, cobble, cobble, cobble, cobble, cobble);
			Choice ingot = Choice.of("iron_ingot");
			craft("iron_pickaxe", 1, true, ingot, ingot, ingot, stick, stick);
			// ciclo de verdade: bloco de ferro <-> barras
			craft("iron_block", 1, true, ingot, ingot, ingot, ingot, ingot, ingot, ingot, ingot, ingot);
			craft("iron_ingot", 9, false, Choice.of("iron_block"));
			smelts.put("iron_ingot", List.of(new SmeltRecipe("iron_from_raw", "iron_ingot", 1, Choice.of("raw_iron"))));
		}

		void craft(String output, int count, boolean table, Choice... inputs) {
			crafts.computeIfAbsent(output, k -> new ArrayList<>())
					.add(new CraftRecipe(output + "_" + crafts.size(), output, count, List.of(inputs), table));
		}

		@Override
		public List<CraftRecipe> craftsFor(String item) {
			return crafts.getOrDefault(item, List.of());
		}

		@Override
		public List<SmeltRecipe> smeltsFor(String item) {
			return smelts.getOrDefault(item, List.of());
		}

		@Override
		public List<Source> sourcesFor(String item) {
			return sources.getOrDefault(item, List.of());
		}

		@Override
		public double fuelValue(String item) {
			return switch (item) {
				case "coal" -> 8;
				case "oak_planks", "birch_planks", "oak_log", "birch_log" -> 1.5;
				case "stick" -> 0.5;
				default -> 0;
			};
		}

		@Override
		public List<String> planks() {
			return PLANKS;
		}

		@Override
		public String craftingTable() {
			return "crafting_table";
		}

		@Override
		public String furnace() {
			return "furnace";
		}
	}

	final Planner planner = new Planner(new Book());
	static final World NOTHING_NEAR = new World(false, false);

	/** Executa o plano num inventário falso, como o jogo faria. */
	static Map<String, Integer> run(Plan plan, Map<String, Integer> start, String gatherAs) {
		Map<String, Integer> inv = new HashMap<>(start);
		for (Step step : plan.steps()) {
			switch (step) {
				case Gather g -> inv.merge(gatherAs != null && g.source().items().contains(gatherAs)
						? gatherAs : g.item(), g.count(), Integer::sum);
				case Craft c -> {
					for (Choice in : c.recipe().inputs()) {
						take(inv, in, c.times());
					}
					inv.merge(c.recipe().output(), c.times() * c.recipe().count(), Integer::sum);
				}
				case Smelt s -> {
					take(inv, s.recipe().input(), s.times());
					inv.merge(s.recipe().output(), s.times() * s.recipe().count(), Integer::sum);
				}
			}
		}
		return inv;
	}

	static void take(Map<String, Integer> inv, Choice c, int n) {
		for (String item : c.items()) {
			int have = inv.getOrDefault(item, 0);
			int used = Math.min(have, n);
			inv.put(item, have - used);
			n -= used;
		}
		assertEquals(0, n, "faltou " + c.items());
	}

	static List<String> describe(Plan plan) {
		List<String> out = new ArrayList<>();
		for (Step s : plan.steps()) {
			out.add(switch (s) {
				case Gather g -> "gather " + g.count();
				case Craft c -> "craft " + c.recipe().output() + " x" + c.times();
				case Smelt m -> "smelt " + m.recipe().output() + " x" + m.times();
			});
		}
		return out;
	}

	@Test
	void alreadyHasIt() {
		Plan plan = planner.plan("stick", 2, Map.of("stick", 5), NOTHING_NEAR);
		assertTrue(plan.done());
	}

	@Test
	void sticksFromNothingNeedOneLog() {
		Plan plan = planner.plan("stick", 4, Map.of(), NOTHING_NEAR);
		assertEquals(List.of("gather 1", "craft oak_planks x1", "craft stick x1"), describe(plan));
	}

	@Test
	void usesTheWoodSheHas() {
		Plan plan = planner.plan("stick", 4, Map.of("birch_log", 1), NOTHING_NEAR);
		assertEquals(List.of("craft birch_planks x1", "craft stick x1"), describe(plan));
	}

	@Test
	void stonePickaxeFromScratch() {
		Plan plan = planner.plan("stone_pickaxe", 1, Map.of(), NOTHING_NEAR);
		assertTrue(plan.possible(), String.valueOf(plan.failure()));
		System.out.println("    plano: " + describe(plan));
		Map<String, Integer> end = run(plan, Map.of(), null);
		assertTrue(end.getOrDefault("stone_pickaxe", 0) >= 1, "sem picareta: " + describe(plan));
		// Primeiro passo é pegar madeira; a bancada é feita uma vez só.
		assertTrue(plan.next() instanceof Gather g && g.source().items().contains("oak_log"));
		long tables = plan.steps().stream()
				.filter(s -> s instanceof Craft c && c.recipe().output().equals("crafting_table")).count();
		assertEquals(1L, tables);
		// A picareta de madeira vem antes de minerar pedra.
		List<String> d = describe(plan);
		assertTrue(d.indexOf("craft wooden_pickaxe x1") < d.indexOf("craft stone_pickaxe x1"), d.toString());
	}

	@Test
	void gatheredWoodCanBeAnyLogAndPlanStillWorks() {
		Plan first = planner.plan("stone_pickaxe", 1, Map.of(), NOTHING_NEAR);
		Gather g = (Gather) first.next();
		// Ela achou bétula em vez de carvalho: o replanejamento usa a bétula.
		Map<String, Integer> inv = new HashMap<>(Map.of("birch_log", g.count()));
		Plan again = planner.plan("stone_pickaxe", 1, inv, NOTHING_NEAR);
		assertEquals("craft birch_planks x1", describe(again).get(0).replaceAll("x\\d+", "x1"));
		assertTrue(run(again, inv, "birch_log").getOrDefault("stone_pickaxe", 0) >= 1);
	}

	@Test
	void tableNearbyIsReused() {
		Map<String, Integer> inv = Map.of("oak_planks", 3, "stick", 2);
		Plan plan = planner.plan("wooden_pickaxe", 1, inv, new World(true, false));
		assertEquals(List.of("craft wooden_pickaxe x1"), describe(plan));
	}

	@Test
	void ironNeedsStonePickaxeFurnaceAndFuel() {
		Plan plan = planner.plan("iron_ingot", 3, Map.of("stone_pickaxe", 1), NOTHING_NEAR);
		assertTrue(plan.possible(), String.valueOf(plan.failure()));
		List<String> d = describe(plan);
		assertTrue(d.contains("craft furnace x1"), d.toString());
		assertTrue(d.get(d.size() - 1).equals("smelt iron_ingot x3"), d.toString());
		assertTrue(run(plan, Map.of("stone_pickaxe", 1), null).getOrDefault("iron_ingot", 0) >= 3);
	}

	@Test
	void ironBlockCycleDoesNotLoop() {
		Plan plan = planner.plan("iron_block", 1, Map.of("iron_pickaxe", 1, "coal", 4, "furnace", 1), NOTHING_NEAR);
		assertTrue(plan.possible(), String.valueOf(plan.failure()));
		assertTrue(run(plan, Map.of("iron_pickaxe", 1, "coal", 4, "furnace", 1), null)
				.getOrDefault("iron_block", 0) >= 1);
	}

	@Test
	void impossibleThingsSayWhy() {
		Plan plan = planner.plan("diamond", 1, Map.of(), NOTHING_NEAR);
		assertFalse(plan.possible());
		assertEquals("não sei como conseguir diamante", plan.failure());
	}

	@Test
	void tagWantedAcceptsAnyVariant() {
		Plan plan = planner.plan(new Choice(Book.LOGS), 3, Map.of("birch_log", 1, "oak_log", 1), NOTHING_NEAR);
		assertEquals(List.of("gather 1"), describe(plan));
	}
}

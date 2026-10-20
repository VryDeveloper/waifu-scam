package dev.vry.waifuscam.craft;

import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Knowledge.CraftRecipe;
import dev.vry.waifuscam.craft.Knowledge.SmeltRecipe;
import dev.vry.waifuscam.craft.Knowledge.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descobre o que fazer para ter um item: minerar, craftar, fundir, em que ordem.
 *
 * O plano é refeito depois de cada passo (o que ela minera pode ser outro tipo de
 * tronco, por exemplo), então só o primeiro passo precisa estar certo; o resto
 * serve para saber se o objetivo é possível. Funciona num inventário simulado:
 * cada passo planejado consome os ingredientes e adiciona o resultado.
 */
public final class Planner {

	public sealed interface Step permits Gather, Craft, Smelt {}

	/**
	 * Minerar até ter mais `count` itens da família da fonte. `item` é a variante
	 * que o plano supôs (qualquer outra da família também serve).
	 */
	public record Gather(Source source, String item, int count) implements Step {}

	/** Craftar a receita `times` vezes. */
	public record Craft(CraftRecipe recipe, int times) implements Step {}

	/** Fundir `times` itens com a receita. */
	public record Smelt(SmeltRecipe recipe, int times) implements Step {}

	public record Plan(List<Step> steps, String failure) {
		public boolean done() {
			return failure == null && steps.isEmpty();
		}

		public boolean possible() {
			return failure == null;
		}

		public Step next() {
			return steps.isEmpty() ? null : steps.get(0);
		}
	}

	/** O que existe perto dela (bancada e fornalha já colocadas no mundo). */
	public record World(boolean tableNearby, boolean furnaceNearby) {}

	private static final int MAX_DEPTH = 12;
	/** Quantas variantes de um ingrediente testar (ex.: os 12 tipos de tábua). */
	private static final int MAX_CANDIDATES = 6;
	/** Limite de tentativas por plano, para nunca travar o servidor. */
	private static final int MAX_EXPANSIONS = 20_000;

	private final Knowledge knowledge;
	private int expansions;

	public Planner(Knowledge knowledge) {
		this.knowledge = knowledge;
	}

	/** Plano para ter pelo menos `count` itens aceitos por `wanted` no inventário. */
	public Plan plan(Choice wanted, int count, Map<String, Integer> inventory, World world) {
		expansions = 0;
		Sim sim = new Sim(new HashMap<>(inventory), world.tableNearby(), world.furnaceNearby());
		String failure = obtain(sim, wanted, count, 0, new HashSet<>());
		return failure == null ? new Plan(List.copyOf(sim.steps), null) : new Plan(List.of(), failure);
	}

	public Plan plan(String item, int count, Map<String, Integer> inventory, World world) {
		return plan(Choice.of(item), count, inventory, world);
	}

	// ---- simulação --------------------------------------------------------------

	private static final class Sim {
		final Map<String, Integer> inv;
		boolean table;
		boolean furnace;
		final List<Step> steps = new ArrayList<>();
		int cost; // estimativa de trabalho: blocos minerados pesam mais que crafts

		Sim(Map<String, Integer> inv, boolean table, boolean furnace) {
			this.inv = inv;
			this.table = table;
			this.furnace = furnace;
		}

		Sim copy() {
			Sim s = new Sim(new HashMap<>(inv), table, furnace);
			s.steps.addAll(steps);
			s.cost = cost;
			return s;
		}

		void restore(Sim other) {
			inv.clear();
			inv.putAll(other.inv);
			table = other.table;
			furnace = other.furnace;
			steps.clear();
			steps.addAll(other.steps);
			cost = other.cost;
		}

		int count(Choice c) {
			int n = 0;
			for (String item : c.items()) {
				n += inv.getOrDefault(item, 0);
			}
			return n;
		}

		int count(Set<String> items) {
			int n = 0;
			for (String item : items) {
				n += inv.getOrDefault(item, 0);
			}
			return n;
		}

		void add(String item, int n) {
			inv.merge(item, n, Integer::sum);
		}

		/** Tira n itens aceitos pela escolha, preferindo o que tem mais. */
		void take(Choice c, int n) {
			List<String> order = new ArrayList<>(c.items());
			order.sort(Comparator.comparingInt((String i) -> inv.getOrDefault(i, 0)).reversed());
			for (String item : order) {
				int have = inv.getOrDefault(item, 0);
				int used = Math.min(have, n);
				if (used > 0) {
					inv.put(item, have - used);
					n -= used;
				}
				if (n == 0) {
					return;
				}
			}
		}
	}

	// ---- conseguir itens --------------------------------------------------------

	/** null = deu; senão, o motivo de não dar. */
	private String obtain(Sim sim, Choice wanted, int count, int depth, Set<String> visiting) {
		int missing = count - sim.count(wanted);
		if (missing <= 0) {
			return null;
		}
		if (depth > MAX_DEPTH || ++expansions > MAX_EXPANSIONS) {
			return "isso é complicado demais de fazer";
		}

		// Testa algumas variantes e fica com a mais barata.
		List<String> candidates = rank(sim, wanted);
		String firstFailure = null;
		Sim best = null;
		for (String item : candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()))) {
			if (visiting.contains(item)) {
				continue;
			}
			Sim trial = sim.copy();
			Set<String> path = new HashSet<>(visiting);
			path.add(item);
			String failure = obtainItem(trial, item, missing, depth, path);
			if (failure == null) {
				if (best == null || trial.cost < best.cost) {
					best = trial;
				}
			} else if (firstFailure == null) {
				firstFailure = failure;
			}
		}
		if (best == null) {
			return firstFailure != null ? firstFailure : "não sei como conseguir " + Names.pretty(wanted.items().get(0));
		}
		sim.restore(best);
		return null;
	}

	/** Variantes que ela já tem (ou já tem como fazer) primeiro. */
	private List<String> rank(Sim sim, Choice wanted) {
		List<String> items = new ArrayList<>(wanted.items());
		items.sort(Comparator.comparingInt((String item) -> {
			if (sim.inv.getOrDefault(item, 0) > 0) {
				return 0;
			}
			for (CraftRecipe r : knowledge.craftsFor(item)) {
				boolean ready = true;
				for (Choice in : r.inputs()) {
					if (sim.count(in) == 0) {
						ready = false;
						break;
					}
				}
				if (ready) {
					return 1;
				}
			}
			return 2;
		}));
		return items;
	}

	private String obtainItem(Sim sim, String item, int missing, int depth, Set<String> visiting) {
		String firstFailure = null;

		// 1) minerar (o mais natural para matéria-prima)
		for (Source source : knowledge.sourcesFor(item)) {
			Sim trial = sim.copy();
			String failure = gather(trial, source, item, missing, depth, visiting);
			if (failure == null) {
				sim.restore(trial);
				return null;
			}
			firstFailure = firstFailure == null ? failure : firstFailure;
		}

		// 2) craftar
		for (CraftRecipe recipe : knowledge.craftsFor(item)) {
			Sim trial = sim.copy();
			String failure = craft(trial, recipe, missing, depth, visiting);
			if (failure == null) {
				sim.restore(trial);
				return null;
			}
			firstFailure = firstFailure == null ? failure : firstFailure;
		}

		// 3) fundir
		for (SmeltRecipe recipe : knowledge.smeltsFor(item)) {
			Sim trial = sim.copy();
			String failure = smelt(trial, recipe, missing, depth, visiting);
			if (failure == null) {
				sim.restore(trial);
				return null;
			}
			firstFailure = firstFailure == null ? failure : firstFailure;
		}
		return firstFailure != null ? firstFailure : "não sei como conseguir " + Names.pretty(item);
	}

	private String gather(Sim sim, Source source, String item, int missing, int depth, Set<String> visiting) {
		if (!source.tools().isEmpty()) {
			boolean hasTool = source.tools().stream().anyMatch(t -> sim.inv.getOrDefault(t, 0) > 0);
			if (!hasTool) {
				String failure = obtain(sim, new Choice(source.tools()), 1, depth + 1, visiting);
				if (failure != null) {
					return "preciso de " + Names.pretty(source.tools().get(0)) + " e não consigo fazer: " + failure;
				}
			}
		}
		sim.steps.add(new Gather(source, item, missing));
		sim.add(item, missing);
		sim.cost += (source.hunt() ? 25 : 10) * missing;
		return null;
	}

	private String craft(Sim sim, CraftRecipe recipe, int missing, int depth, Set<String> visiting) {
		int times = (missing + recipe.count() - 1) / recipe.count();

		// Quantos de cada escolha a receita pede (ex.: 3 tábuas + 2 gravetos).
		Map<Choice, Integer> needs = new HashMap<>();
		for (Choice in : recipe.inputs()) {
			needs.merge(in, times, Integer::sum);
		}
		// Conseguir um ingrediente (ou a bancada) pode gastar outro, então repete
		// até tudo estar no inventário ao mesmo tempo.
		for (int pass = 0; ; pass++) {
			if (pass == 3) {
				return "não consegui juntar tudo para " + Names.pretty(recipe.output());
			}
			for (Map.Entry<Choice, Integer> need : needs.entrySet()) {
				String failure = obtain(sim, need.getKey(), need.getValue(), depth + 1, visiting);
				if (failure != null) {
					return failure;
				}
			}
			if (recipe.needsTable() && !sim.table) {
				String table = knowledge.craftingTable();
				if (sim.inv.getOrDefault(table, 0) == 0) {
					String failure = obtain(sim, Choice.of(table), 1, depth + 1, visiting);
					if (failure != null) {
						return "preciso de uma bancada: " + failure;
					}
				}
				// A bancada vai para o chão quando ela for craftar.
				sim.add(table, -1);
				sim.table = true;
			}
			boolean all = true;
			for (Map.Entry<Choice, Integer> need : needs.entrySet()) {
				all &= sim.count(need.getKey()) >= need.getValue();
			}
			if (all) {
				break;
			}
		}
		for (Map.Entry<Choice, Integer> need : needs.entrySet()) {
			sim.take(need.getKey(), need.getValue());
		}
		sim.steps.add(new Craft(recipe, times));
		sim.add(recipe.output(), times * recipe.count());
		sim.cost += 1;
		return null;
	}

	private String smelt(Sim sim, SmeltRecipe recipe, int missing, int depth, Set<String> visiting) {
		int times = (missing + recipe.count() - 1) / recipe.count();
		String failure = obtain(sim, recipe.input(), times, depth + 1, visiting);
		if (failure != null) {
			return failure;
		}
		if (!sim.furnace) {
			String furnace = knowledge.furnace();
			if (sim.inv.getOrDefault(furnace, 0) == 0) {
				failure = obtain(sim, Choice.of(furnace), 1, depth + 1, visiting);
				if (failure != null) {
					return "preciso de uma fornalha: " + failure;
				}
			}
			sim.add(furnace, -1);
			sim.furnace = true;
			failure = obtain(sim, recipe.input(), times, depth + 1, visiting);
			if (failure != null) {
				return failure;
			}
		}
		failure = fuel(sim, times, depth, visiting);
		if (failure != null) {
			return failure;
		}
		sim.take(recipe.input(), times);
		sim.steps.add(new Smelt(recipe, times));
		sim.add(recipe.output(), times * recipe.count());
		sim.cost += 2;
		return null;
	}

	/** Garante combustível para assar `items` itens, gastando do inventário simulado. */
	private String fuel(Sim sim, int items, int depth, Set<String> visiting) {
		double needed = items;
		// Usa primeiro o que já tem, do mais forte para o mais fraco.
		List<String> fuels = new ArrayList<>(sim.inv.keySet());
		fuels.removeIf(f -> knowledge.fuelValue(f) <= 0 || sim.inv.getOrDefault(f, 0) <= 0);
		fuels.sort(Comparator.comparingDouble(knowledge::fuelValue).reversed());
		for (String f : fuels) {
			double value = knowledge.fuelValue(f);
			int have = sim.inv.get(f);
			int use = (int) Math.min(have, Math.ceil(needed / value));
			sim.inv.put(f, have - use);
			needed -= use * value;
			if (needed <= 0) {
				return null;
			}
		}
		// Falta: tábuas saem de qualquer tronco.
		Choice planks = new Choice(knowledge.planks());
		int more = (int) Math.ceil(needed / 1.5);
		String failure = obtain(sim, planks, more + sim.count(planks), depth + 1, visiting);
		if (failure != null) {
			return "sem combustível: " + failure;
		}
		sim.take(planks, more);
		return null;
	}
}

package dev.vry.waifuscam.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Knowledge.CraftRecipe;
import dev.vry.waifuscam.craft.Knowledge.SmeltRecipe;
import dev.vry.waifuscam.craft.Knowledge.Source;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.craft.Planner;
import dev.vry.waifuscam.craft.Planner.Craft;
import dev.vry.waifuscam.craft.Planner.Gather;
import dev.vry.waifuscam.craft.Planner.Plan;
import dev.vry.waifuscam.craft.Planner.Smelt;
import dev.vry.waifuscam.craft.Planner.Step;
import dev.vry.waifuscam.craft.Planner.Withdraw;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perguntas da Mente que o corpo responde na hora, sem fazer nada no mundo.
 *
 * "plan": como conseguir um item agora — a receita do jogo e o passo a passo
 * que ela faria com o inventário dela e com o que tem por perto (bancada,
 * fornalha). É o mesmo planejador do obtain, então o que ela diz que vai fazer
 * é o que ela faz.
 */
public final class Queries {
	private Queries() {}

	public static JsonObject answer(String query, JsonObject args, MinecraftServer server, ServerPlayer me) {
		JsonObject out = new JsonObject();
		if (!"plan".equals(query)) {
			out.addProperty("ok", false);
			out.addProperty("failure", "pergunta desconhecida: " + query);
			return out;
		}
		String raw = args != null && args.has("item") ? args.get("item").getAsString() : "";
		int count = args != null && args.has("count") ? Math.max(1, args.get("count").getAsInt()) : 1;
		return plan(raw, count, server, me);
	}

	/** O que existe perto dela que o planejador leva em conta. */
	public static Planner.World world(ServerPlayer me) {
		boolean table = !BlockFinder.find(me.level(), me.blockPosition(), 12, 1,
				p -> me.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)).isEmpty();
		boolean furnace = !BlockFinder.find(me.level(), me.blockPosition(), 12, 1,
				p -> me.level().getBlockState(p).is(Blocks.FURNACE)).isEmpty();
		var mod = dev.vry.waifuscam.WaifuScamMod.instance();
		boolean chests = mod == null || mod.config() == null || mod.config().useChests;
		return new Planner.World(table, furnace, chests ? ChestSkill.stored(me.level(), me.blockPosition(), 16) : Map.of());
	}

	public static JsonObject plan(String raw, int count, MinecraftServer server, ServerPlayer me) {
		JsonObject out = new JsonObject();
		List<String> items = Stacks.resolve(raw);
		if (items.isEmpty()) {
			out.addProperty("ok", false);
			out.addProperty("failure", "não conheço o item " + raw);
			return out;
		}
		String item = items.get(0);
		GameKnowledge k = GameKnowledge.of(server);
		Map<String, Integer> inv = Stacks.counts(me.getInventory());
		Planner.World world = world(me);
		out.addProperty("item", item.replace("minecraft:", ""));
		out.addProperty("count", count);
		out.addProperty("have", Stacks.countWithWorn(me, st -> items.contains(Stacks.id(st))));
		out.addProperty("table_nearby", world.tableNearby());
		out.addProperty("furnace_nearby", world.furnaceNearby());
		JsonObject recipe = recipe(k, item);
		if (recipe != null) {
			out.add("recipe", recipe);
		}

		Plan plan = new Planner(k).plan(new Choice(items), count, inv, world);
		out.addProperty("ok", plan.possible());
		if (!plan.possible()) {
			out.addProperty("failure", plan.failure());
			return out;
		}
		JsonArray steps = new JsonArray();
		for (Step step : merged(plan.steps())) {
			steps.add(step(step));
		}
		out.add("steps", steps);
		return out;
	}

	/** Uma receita do jogo para o item (a primeira de bancada, senão a de fornalha, senão de onde sai). */
	static JsonObject recipe(GameKnowledge k, String item) {
		List<CraftRecipe> crafts = k.craftsFor(item);
		if (!crafts.isEmpty()) {
			CraftRecipe r = crafts.get(0);
			JsonObject o = new JsonObject();
			o.addProperty("kind", "craft");
			o.addProperty("table", r.needsTable());
			o.addProperty("makes", r.count());
			o.add("inputs", inputs(r.inputs(), 1));
			if (crafts.size() > 1) {
				o.addProperty("other_recipes", crafts.size() - 1);
			}
			return o;
		}
		List<SmeltRecipe> smelts = k.smeltsFor(item);
		if (!smelts.isEmpty()) {
			JsonObject o = new JsonObject();
			o.addProperty("kind", "smelt");
			o.add("input", choice(smelts.get(0).input(), 1));
			return o;
		}
		List<Source> sources = k.sourcesFor(item);
		if (!sources.isEmpty()) {
			Source s = sources.get(0);
			JsonObject o = new JsonObject();
			o.addProperty("kind", s.hunt() ? "hunt" : "gather");
			o.add("from", ids(new ArrayList<>(s.blocks()), 3));
			if (!s.tools().isEmpty()) {
				o.addProperty("tool", s.tools().get(0).replace("minecraft:", ""));
			}
			return o;
		}
		return null;
	}

	/** Coletas seguidas da mesma fonte viram uma só, como o obtain faz. */
	private static List<Step> merged(List<Step> steps) {
		List<Step> out = new ArrayList<>();
		Map<Source, Integer> gatherAt = new LinkedHashMap<>();
		for (Step s : steps) {
			if (s instanceof Gather g && gatherAt.containsKey(g.source())) {
				int i = gatherAt.get(g.source());
				Gather prev = (Gather) out.get(i);
				out.set(i, new Gather(prev.source(), prev.item(), prev.count() + g.count()));
				continue;
			}
			if (s instanceof Gather g) {
				gatherAt.put(g.source(), out.size());
			}
			out.add(s);
		}
		return out;
	}

	private static JsonObject step(Step step) {
		JsonObject o = new JsonObject();
		switch (step) {
			case Withdraw w -> {
				o.addProperty("do", "withdraw");
				o.addProperty("item", w.item().replace("minecraft:", ""));
				o.addProperty("count", w.count());
			}
			case Gather g -> {
				o.addProperty("do", g.source().hunt() ? "hunt" : "gather");
				o.addProperty("item", g.item().replace("minecraft:", ""));
				o.addProperty("count", g.count());
				o.add("from", ids(new ArrayList<>(g.source().blocks()), 2));
				if (!g.source().tools().isEmpty()) {
					o.addProperty("tool", g.source().tools().get(0).replace("minecraft:", ""));
				}
			}
			case Craft c -> {
				o.addProperty("do", "craft");
				o.addProperty("item", c.recipe().output().replace("minecraft:", ""));
				o.addProperty("count", c.times() * c.recipe().count());
				o.addProperty("table", c.recipe().needsTable());
				o.add("inputs", inputs(c.recipe().inputs(), c.times()));
			}
			case Smelt m -> {
				o.addProperty("do", "smelt");
				o.addProperty("item", m.recipe().output().replace("minecraft:", ""));
				o.addProperty("count", m.times() * m.recipe().count());
				o.add("input", choice(m.recipe().input(), m.times()));
			}
		}
		return o;
	}

	/** Ingredientes agrupados: 3 tábuas + 2 gravetos. */
	private static JsonArray inputs(List<Choice> choices, int times) {
		Map<Choice, Integer> counts = new LinkedHashMap<>();
		for (Choice c : choices) {
			counts.merge(c, times, Integer::sum);
		}
		JsonArray out = new JsonArray();
		counts.forEach((c, n) -> out.add(choice(c, n)));
		return out;
	}

	private static JsonObject choice(Choice c, int n) {
		JsonObject o = new JsonObject();
		o.addProperty("item", c.items().get(0).replace("minecraft:", ""));
		o.addProperty("count", n);
		if (c.items().size() > 1) {
			o.addProperty("any", true); // qualquer variante serve (qualquer tábua)
		}
		return o;
	}

	private static JsonArray ids(List<String> ids, int limit) {
		JsonArray out = new JsonArray();
		ids.stream().limit(limit).forEach(id -> out.add(id.replace("minecraft:", "")));
		return out;
	}

	/** Texto curto do plano, para o /waifu plan. */
	public static String text(JsonObject plan) {
		if (!plan.get("ok").getAsBoolean()) {
			return "Não dá: " + plan.get("failure").getAsString();
		}
		JsonArray steps = plan.getAsJsonArray("steps");
		if (steps.isEmpty()) {
			return "Ela já tem.";
		}
		List<String> parts = new ArrayList<>();
		for (var el : steps) {
			JsonObject s = el.getAsJsonObject();
			String what = Names.pretty(s.get("item").getAsString());
			String verb = switch (s.get("do").getAsString()) {
				case "hunt" -> "caçar";
				case "gather" -> "pegar";
				case "smelt" -> "assar";
				case "withdraw" -> "tirar do baú";
				default -> "craftar";
			};
			parts.add(verb + " " + s.get("count").getAsInt() + " " + what);
		}
		return String.join(" → ", parts);
	}
}

package dev.vry.waifuscam.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Knowledge.Source;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catálogo das habilidades do corpo. Vai para a Mente no hello, que transforma
 * cada entrada numa ação que a IA pode escolher. Para criar uma habilidade nova,
 * basta registrar aqui: a Mente descobre sozinha.
 *
 * A ordem das propriedades em params é a ordem dos argumentos na linha de ação
 * da IA ("[ação: give Vry torch 4]").
 */
public final class SkillRegistry {

	/** Cria a habilidade a partir dos argumentos vindos da Mente. */
	@FunctionalInterface
	public interface Factory {
		Skill create(JsonObject args, MinecraftServer server, ServerPlayer companion) throws SkillException;
	}

	public record Spec(String name, String description, JsonObject params, Factory factory) {}

	public static final class SkillException extends Exception {
		private static final long serialVersionUID = 1L;

		public SkillException(String message) {
			super(message);
		}
	}

	private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

	private static final String ITEM = "\"item\":{\"type\":\"string\",\"description\":\"id do item em inglês, ex.: stone_pickaxe, oak_log, torch, iron_ingot; tag com #, ex.: #logs\"}";
	private static final String COUNT = "\"count\":{\"type\":\"integer\",\"minimum\":1}";
	private static final String PLAYER = "\"player\":{\"type\":\"string\",\"description\":\"nome do player\"}";

	static {
		// ---- fase 3: movimento ----

		register(new Spec("follow",
				"Seguir um player, ficando perto dele até alguém pedir para parar. Use quando pedirem para seguir ou acompanhar.",
				params("{\"type\":\"object\",\"properties\":{" + PLAYER + "},\"required\":[\"player\"]}"),
				(args, server, me) -> new FollowSkill(player(server, args), 2.5)));

		register(new Spec("goto",
				"Ir até um player (ex.: quando pedem 'vem aqui') ou até coordenadas x, y, z, e parar lá.",
				params("{\"type\":\"object\",\"properties\":{" + PLAYER
						+ ",\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}}}"),
				(args, server, me) -> {
					if (args.has("player")) {
						ServerPlayer target = player(server, args);
						if (target.level() != me.level()) {
							throw new SkillException(target.getGameProfile().name() + " está em outra dimensão");
						}
						Pos goal = SkillContext.feetOf(target, new LevelTerrain(target.level()));
						return new GotoSkill(goal, 2.0, target.getGameProfile().name());
					}
					if (args.has("x") && args.has("y") && args.has("z")) {
						Pos goal = new Pos((int) Math.floor(args.get("x").getAsDouble()),
								(int) Math.floor(args.get("y").getAsDouble()),
								(int) Math.floor(args.get("z").getAsDouble()));
						return new GotoSkill(goal, 1.0, goal.x() + ", " + goal.y() + ", " + goal.z());
					}
					throw new SkillException("faltou dizer para onde (player ou x, y, z)");
				}));

		// ---- fase 4: mãos ----

		register(new Spec("obtain",
				"Ficar com pelo menos `count` do item no inventário, fazendo o que precisar: minerar, craftar, fundir. "
						+ "Ex.: obtain stone_pickaxe 1 faz uma picareta de pedra do zero (madeira, bancada, picareta de madeira, pedra).",
				params("{\"type\":\"object\",\"properties\":{" + ITEM + "," + COUNT + "},\"required\":[\"item\"]}"),
				(args, server, me) -> new ObtainSkill(choice(args), count(args), label(args))));

		register(new Spec("craft",
				"Craftar mais `count` do item, mesmo se já tiver, conseguindo os ingredientes que faltarem.",
				params("{\"type\":\"object\",\"properties\":{" + ITEM + "," + COUNT + "},\"required\":[\"item\"]}"),
				(args, server, me) -> {
					Choice c = choice(args);
					int have = Stacks.count(me.getInventory(), c.items());
					return new ObtainSkill(c, have + count(args), label(args));
				}));

		register(new Spec("collect",
				"Minerar ou coletar mais `count` de um recurso natural: #logs (madeira), cobblestone, dirt, sand, gravel, coal, raw_iron...",
				params("{\"type\":\"object\",\"properties\":{" + ITEM + "," + COUNT + "},\"required\":[\"item\"]}"),
				(args, server, me) -> {
					Choice c = choice(args);
					GameKnowledge k = GameKnowledge.of(server);
					Set<String> items = new LinkedHashSet<>();
					Set<String> blocks = new LinkedHashSet<>();
					for (String item : c.items()) {
						for (Source src : k.sourcesFor(item)) {
							items.addAll(src.items());
							blocks.addAll(src.blocks());
						}
					}
					if (blocks.isEmpty()) {
						throw new SkillException(label(args) + " não sai de nenhum bloco que eu conheça");
					}
					return new CollectSkill(items, blocks, count(args), label(args));
				}));

		register(new Spec("eat",
				"Comer a melhor comida que tiver no inventário.",
				params("{\"type\":\"object\",\"properties\":{}}"),
				(args, server, me) -> new EatSkill(false)));

		register(new Spec("give",
				"Entregar itens para um player (consegue antes, se não tiver). Use quando pedirem 'me dá' ou 'me passa'.",
				params("{\"type\":\"object\",\"properties\":{" + PLAYER + "," + ITEM + "," + COUNT + "},\"required\":[\"player\",\"item\"]}"),
				(args, server, me) -> new GiveSkill(player(server, args), choice(args), count(args), label(args))));

		register(new Spec("deposit",
				"Guardar itens no baú mais perto. Sem item, guarda tudo menos ferramentas, armas, armadura e comida.",
				params("{\"type\":\"object\",\"properties\":{" + ITEM + "," + COUNT + "}}"),
				(args, server, me) -> args.has("item")
						? new ChestSkill(ChestSkill.Mode.DEPOSIT, choice(args), args.has("count") ? count(args) : 0, label(args))
						: new ChestSkill(ChestSkill.Mode.DEPOSIT, null, 0, "as coisas")));

		register(new Spec("withdraw",
				"Pegar itens do baú mais perto.",
				params("{\"type\":\"object\",\"properties\":{" + ITEM + "," + COUNT + "},\"required\":[\"item\"]}"),
				(args, server, me) -> new ChestSkill(ChestSkill.Mode.WITHDRAW, choice(args), count(args), label(args))));

		// ---- fase 5: sobreviver ----

		register(new Spec("hunt",
				"Caçar animais para comida ou material: cow, pig, sheep, chicken, rabbit. Nunca caça pet com nome nem filhote.",
				params("{\"type\":\"object\",\"properties\":{\"animal\":{\"type\":\"string\",\"description\":\"cow, pig, sheep, chicken ou rabbit\"}," + COUNT + "},\"required\":[\"animal\"]}"),
				(args, server, me) -> {
					String animal = args.get("animal").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
					animal = switch (animal) {
						case "vaca", "vacas", "cows" -> "cow";
						case "porco", "porcos", "pigs" -> "pig";
						case "ovelha", "ovelhas" -> "sheep";
						case "galinha", "galinhas", "frango", "chickens" -> "chicken";
						case "coelho", "coelhos", "rabbits" -> "rabbit";
						default -> animal;
					};
					String id = animal.contains(":") ? animal : "minecraft:" + animal;
					if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(net.minecraft.resources.Identifier.tryParse(id))) {
						throw new SkillException("não conheço o bicho '" + animal + "'");
					}
					return new HuntSkill(Set.of(id), Set.of(), count(args), Names.pretty(animal));
				}));

		register(new Spec("attack",
				"Lutar contra os monstros hostis por perto (count = quantos derrotar).",
				params("{\"type\":\"object\",\"properties\":{" + COUNT + "}}"),
				(args, server, me) -> HuntSkill.hostiles(me, count(args))));

		register(new Spec("shelter",
				"Passar a noite em segurança: cava um buraco, fecha em cima e sai de manhã (numa casa, só espera).",
				params("{\"type\":\"object\",\"properties\":{}}"),
				(args, server, me) -> new ShelterSkill()));
	}

	private SkillRegistry() {}

	public static void register(Spec spec) {
		SPECS.put(spec.name(), spec);
	}

	public static Skill create(String name, JsonObject args, MinecraftServer server, ServerPlayer companion)
			throws SkillException {
		Spec spec = SPECS.get(name);
		if (spec == null) {
			throw new SkillException("não sei fazer '" + name + "'");
		}
		return spec.factory().create(args == null ? new JsonObject() : args, server, companion);
	}

	/** O catálogo no formato do protocolo: [{name, description, params}]. "stop" é embutido. */
	public static JsonArray catalog() {
		JsonArray out = new JsonArray();
		for (Spec spec : SPECS.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", spec.name());
			o.addProperty("description", spec.description());
			o.add("params", spec.params());
			out.add(o);
		}
		JsonObject stop = new JsonObject();
		stop.addProperty("name", "stop");
		stop.addProperty("description", "Parar o que está fazendo e ficar parada onde está.");
		stop.add("params", params("{\"type\":\"object\",\"properties\":{}}"));
		out.add(stop);
		return out;
	}

	private static JsonObject params(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	/** O item pedido (id, tag ou apelido) como escolha do planejador. */
	static Choice choice(JsonObject args) throws SkillException {
		if (!args.has("item")) {
			throw new SkillException("faltou dizer qual item");
		}
		String raw = args.get("item").getAsString();
		List<String> items = Stacks.resolve(raw);
		if (items.isEmpty()) {
			throw new SkillException("não conheço o item '" + raw + "'");
		}
		return new Choice(items);
	}

	static int count(JsonObject args) {
		if (!args.has("count")) {
			return 1;
		}
		try {
			return Math.max(1, Math.min(576, (int) Math.round(args.get("count").getAsDouble())));
		} catch (RuntimeException e) {
			return 1;
		}
	}

	static String label(JsonObject args) {
		return args.has("item") ? Names.pretty(Stacks.normalize(args.get("item").getAsString())) : "itens";
	}

	private static ServerPlayer player(MinecraftServer server, JsonObject args) throws SkillException {
		if (!args.has("player")) {
			throw new SkillException("faltou o nome do player");
		}
		String name = args.get("player").getAsString();
		ServerPlayer p = server.getPlayerList().getPlayerByName(name);
		if (p == null) {
			throw new SkillException(name + " não está online");
		}
		return p;
	}
}

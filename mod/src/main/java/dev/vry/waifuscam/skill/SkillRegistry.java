package dev.vry.waifuscam.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catálogo das habilidades do corpo. Vai para a Mente no hello, que transforma
 * cada entrada numa ação que a IA pode escolher. Para criar uma habilidade nova,
 * basta registrar aqui: a Mente descobre sozinha.
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

	static {
		register(new Spec("follow",
				"Seguir um player, ficando perto dele até alguém pedir para parar. Use quando pedirem para seguir ou acompanhar.",
				params("""
						{"type":"object","properties":{"player":{"type":"string","description":"nome do player a seguir"}},"required":["player"]}
						"""),
				(args, server, me) -> new FollowSkill(player(server, args), 2.5)));

		register(new Spec("goto",
				"Ir até um player (ex.: quando pedem 'vem aqui') ou até coordenadas x, y, z, e parar lá.",
				params("""
						{"type":"object","properties":{
						 "player":{"type":"string","description":"nome do player para ir até ele"},
						 "x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}}}
						"""),
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

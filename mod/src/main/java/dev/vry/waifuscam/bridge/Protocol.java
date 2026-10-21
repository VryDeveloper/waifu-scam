package dev.vry.waifuscam.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Mensagens do protocolo Corpo ↔ Mente (v1). Ver protocol/v1.schema.json.
 *
 * Toda mensagem é um objeto JSON com "v" (versão), "bot" (id da personagem, ou null
 * antes do welcome) e "type". Esta classe não depende do Minecraft, para poder ser
 * testada fora do jogo.
 */
public final class Protocol {
	public static final int VERSION = 1;
	private static final Gson GSON = new Gson();

	private Protocol() {}

	public static JsonObject envelope(String type, String bot) {
		JsonObject o = new JsonObject();
		o.addProperty("v", VERSION);
		o.add("bot", bot == null ? JsonNull.INSTANCE : GSON.toJsonTree(bot));
		o.addProperty("type", type);
		return o;
	}

	/** Primeira mensagem do Corpo ao conectar. */
	public static String hello(String body, String bodyVersion, String minecraft) {
		return hello(body, bodyVersion, minecraft, null);
	}

	/** skills = catálogo [{name, description, params}] que a Mente pode pedir (fase 3). */
	public static String hello(String body, String bodyVersion, String minecraft, JsonArray skills) {
		JsonObject o = envelope("hello", null);
		o.addProperty("protocol", VERSION);
		o.addProperty("body", body);
		o.addProperty("body_version", bodyVersion);
		o.addProperty("minecraft", minecraft);
		if (skills != null) {
			o.add("skills", skills);
		}
		return o.toString();
	}

	/**
	 * Uma habilidade terminou. reason == null: deu certo (skill.done); senão,
	 * skill.failed. cancelled = foi trocada por outra ou mandaram parar (não é erro).
	 */
	public static String skillEnded(String bot, String id, String skill, String reason, boolean cancelled) {
		JsonObject o = envelope(reason == null ? "skill.done" : "skill.failed", bot);
		o.addProperty("id", id);
		o.addProperty("skill", skill);
		if (reason != null) {
			o.addProperty("reason", reason);
			o.addProperty("cancelled", cancelled);
		}
		return o.toString();
	}

	public static String ping(String bot, String id, long sentAtMillis) {
		JsonObject o = envelope("ping", bot);
		o.addProperty("id", id);
		o.addProperty("t", sentAtMillis);
		return o.toString();
	}

	public static String pong(String bot, String id) {
		JsonObject o = envelope("pong", bot);
		o.addProperty("id", id);
		return o.toString();
	}

	/** Algo aconteceu no jogo (chat, morte, respawn...). detail pode ser null. */
	public static String event(String bot, String event, JsonObject fields) {
		JsonObject o = envelope("event", bot);
		o.addProperty("event", event);
		if (fields != null) {
			for (var e : fields.entrySet()) {
				o.add(e.getKey(), e.getValue());
			}
		}
		return o.toString();
	}

	public static String chatEvent(String bot, String from, String text) {
		JsonObject f = new JsonObject();
		f.addProperty("from", from);
		f.addProperty("text", text);
		return event(bot, "chat", f);
	}

	/** Resposta a uma pergunta da Mente (query). */
	public static String queryResult(String bot, String id, JsonObject result) {
		JsonObject o = envelope("query.result", bot);
		o.addProperty("id", id);
		for (var e : result.entrySet()) {
			o.add(e.getKey(), e.getValue());
		}
		return o.toString();
	}

	/** Snapshot periódico do corpo. */
	public static String state(String bot, JsonObject state) {
		JsonObject o = envelope("state", bot);
		for (var e : state.entrySet()) {
			o.add(e.getKey(), e.getValue());
		}
		return o.toString();
	}

	/** Lê e valida o envelope. Lança {@link ProtocolException} se a mensagem não for v1 válida. */
	public static JsonObject parse(String raw) throws ProtocolException {
		JsonElement el;
		try {
			el = JsonParser.parseString(raw);
		} catch (JsonParseException e) {
			throw new ProtocolException("JSON inválido: " + e.getMessage());
		}
		if (!el.isJsonObject()) {
			throw new ProtocolException("mensagem precisa ser um objeto JSON");
		}
		JsonObject o = el.getAsJsonObject();
		if (!o.has("v") || !o.get("v").isJsonPrimitive() || o.get("v").getAsInt() != VERSION) {
			throw new ProtocolException("versão de protocolo não suportada (esperado v" + VERSION + ")");
		}
		if (!o.has("type") || !o.get("type").isJsonPrimitive()) {
			throw new ProtocolException("campo 'type' ausente");
		}
		return o;
	}

	public static String type(JsonObject msg) {
		return msg.get("type").getAsString();
	}

	/** Lê um campo de texto opcional; devolve null se não existir ou for null. */
	public static String string(JsonObject msg, String field) {
		JsonElement el = msg.get(field);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	public static final class ProtocolException extends Exception {
		private static final long serialVersionUID = 1L;

		public ProtocolException(String message) {
			super(message);
		}
	}
}

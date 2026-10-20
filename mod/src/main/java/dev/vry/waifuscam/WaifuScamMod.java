package dev.vry.waifuscam;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vry.waifuscam.bot.CompanionManager;
import dev.vry.waifuscam.bot.CompanionPlayer;
import dev.vry.waifuscam.bot.SkinData;
import dev.vry.waifuscam.bridge.MindClient;
import dev.vry.waifuscam.bridge.Protocol;
import dev.vry.waifuscam.command.WaifuCommand;
import dev.vry.waifuscam.config.ModConfig;
import dev.vry.waifuscam.skill.Skill;
import dev.vry.waifuscam.skill.SkillRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Entrada do mod (Corpo).
 *
 * Fase 0: liga na Mente (hello → welcome) e mede o ping.
 * Fase 1: coloca a personagem no mundo ao abrir a LAN, repassa o chat e o estado
 * dela para a Mente e fala no chat quando a Mente manda.
 * Fase 2: o estado também leva quem está online, o horário e o clima.
 * Fase 3: habilidades de movimento (seguir, ir até), pedidas pela Mente ou por comando.
 */
public final class WaifuScamMod implements ModInitializer {
	public static final String MOD_ID = "waifuscam";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static WaifuScamMod instance;

	private ModConfig config;
	private MinecraftServer server;
	private MindClient mind;
	private CompanionManager companions;

	/** Id e nome da personagem, recebidos no welcome da Mente. */
	private volatile String botId;
	private volatile String characterName;
	private volatile long lastPingMillis = -1;
	/** A entrada automática acontece uma vez por sessão; depois de /waifu despawn ela não volta sozinha. */
	private boolean autoJoined;

	private final Map<String, Pending> pendingPings = new ConcurrentHashMap<>();

	private record Pending(long sentAt, LongConsumer onPong) {}

	public static WaifuScamMod instance() {
		return instance;
	}

	@Override
	public void onInitialize() {
		instance = this;
		config = ModConfig.load(FabricLoader.getInstance().getConfigDir());
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> onServerStopping());
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			if (!(sender instanceof CompanionPlayer)) {
				sendToMind(Protocol.chatEvent(botId, sender.getGameProfile().name(), message.signedContent()));
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				WaifuCommand.register(dispatcher, this));
		LOGGER.info("Waifu Scam carregado. A Mente é esperada em {}", config.mindUrl);
	}

	// ---- ciclo do servidor ----

	private void onServerStarted(MinecraftServer server) {
		this.server = server;
		this.autoJoined = false;
		this.companions = new CompanionManager(server, this::sendEvent,
				(id, skill, reason, cancelled) -> sendToMind(Protocol.skillEnded(botId, id, skill, reason, cancelled)));
		mind = new MindClient(URI.create(config.mindUrl), Duration.ofSeconds(config.reconnectSeconds),
				new MindHandler(), LOGGER::info);
		mind.start();
	}

	private void onServerStopping() {
		if (mind != null) {
			mind.close();
			mind = null;
		}
		botId = null;
		characterName = null;
		pendingPings.clear();
		companions = null;
		server = null;
	}

	private void onServerTick(MinecraftServer server) {
		if (companions == null) {
			return;
		}
		companions.tick();
		int tick = server.getTickCount();
		if (tick % 20 == 0) {
			maybeAutoJoin();
		}
		if (tick % 40 == 0) {
			sendState();
		}
	}

	/** Entrada automática conforme join_on: "lan" espera a LAN abrir; "world" entra assim que houver um player. */
	private void maybeAutoJoin() {
		if (autoJoined || config.joinOn.equals("command") || !companions.isConfigured() || companions.isOnline()) {
			return;
		}
		if (config.joinOn.equals("lan") && !server.isPublished()) {
			return;
		}
		ServerPlayer host = firstRealPlayer();
		if (host == null) {
			return;
		}
		String error = companions.spawn(host);
		autoJoined = true;
		if (error != null) {
			LOGGER.warn("A personagem não pôde entrar: {}", error);
		}
	}

	private ServerPlayer firstRealPlayer() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof CompanionPlayer)) {
				return p;
			}
		}
		return null;
	}

	// ---- mensagens para a Mente ----

	private void sendToMind(String json) {
		MindClient m = mind;
		if (m != null && m.isConnected()) {
			m.send(json);
		}
	}

	private void sendEvent(String event, String detail) {
		JsonObject fields = new JsonObject();
		if (detail != null) {
			fields.addProperty("message", detail);
		}
		sendToMind(Protocol.event(botId, event, fields));
	}

	private void sendState() {
		CompanionPlayer p = companions.player();
		JsonObject s = new JsonObject();
		s.addProperty("online", p != null);

		// Contexto do mundo, para a conversa (fase 2)
		JsonArray players = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			players.add(player.getGameProfile().name());
		}
		s.add("players", players);
		ServerLevel overworld = server.overworld();
		s.addProperty("time_of_day", overworld.getOverworldClockTime() % 24000L);
		s.addProperty("weather", overworld.isThundering() ? "thunder" : overworld.isRaining() ? "rain" : "clear");

		if (p != null) {
			s.addProperty("alive", !p.isDeadOrDying());
			s.addProperty("health", p.getHealth());
			s.addProperty("max_health", p.getMaxHealth());
			s.addProperty("food", p.getFoodData().getFoodLevel());
			s.addProperty("saturation", p.getFoodData().getSaturationLevel());
			s.addProperty("x", p.getX());
			s.addProperty("y", p.getY());
			s.addProperty("z", p.getZ());
			s.addProperty("dimension", p.level().dimension().identifier().toString());
			Skill current = companions.currentSkill();
			if (current != null) {
				JsonObject skill = new JsonObject();
				skill.addProperty("id", companions.currentSkillId());
				skill.addProperty("name", current.name());
				skill.addProperty("doing", current.describe());
				s.add("skill", skill);
			}
		}
		sendToMind(Protocol.state(botId, s));
	}

	/** A personagem fala no chat. Mensagem de sistema no formato de chat de player. */
	private void say(String text) {
		if (server == null || companions == null || !companions.isOnline() || text == null || text.isBlank()) {
			return;
		}
		Component line = Component.literal("<" + companions.username() + "> " + text);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof CompanionPlayer)) {
				p.sendSystemMessage(line);
			}
		}
		server.sendSystemMessage(line); // também no log do servidor
	}

	/**
	 * Começa uma habilidade pedida pela Mente ou por comando. Roda na thread do
	 * servidor. Devolve o motivo do erro, ou null se começou.
	 */
	public String startSkill(String id, String name, JsonObject args) {
		CompanionManager c = companions;
		if (c == null || server == null) {
			return "servidor ainda iniciando";
		}
		String error = null;
		CompanionPlayer me = c.player();
		if (me == null || me.isDeadOrDying()) {
			error = "não estou no mundo agora";
		} else if ("stop".equals(name)) {
			c.stopSkill();
			sendToMind(Protocol.skillEnded(botId, id, name, null, false));
			return null;
		} else {
			try {
				c.startSkill(id, SkillRegistry.create(name, args, server, me));
			} catch (SkillRegistry.SkillException e) {
				error = e.getMessage();
			}
		}
		if (error != null) {
			sendToMind(Protocol.skillEnded(botId, id, name, error, false));
		}
		return error;
	}

	// ---- usado pelos comandos e pelos mixins ----

	public CompanionManager companions() {
		return companions;
	}

	public boolean isMindConnected() {
		return mind != null && mind.isConnected();
	}

	public String characterName() {
		return characterName;
	}

	public String mindUrl() {
		return config.mindUrl;
	}

	public long lastPingMillis() {
		return lastPingMillis;
	}

	/** Manda um ping; onPong recebe a latência em ms, já na thread do servidor. */
	public boolean ping(LongConsumer onPong) {
		if (!isMindConnected()) {
			return false;
		}
		String id = UUID.randomUUID().toString();
		pendingPings.put(id, new Pending(System.currentTimeMillis(), onPong));
		return mind.send(Protocol.ping(botId, id, System.currentTimeMillis()));
	}

	// ---- mensagens da Mente (chegam em threads de rede) ----

	private final class MindHandler implements MindClient.Handler {
		@Override
		public void onConnected() {
			String modVersion = FabricLoader.getInstance().getModContainer(MOD_ID)
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
			mind.send(Protocol.hello("fabric", modVersion, mcVersion, SkillRegistry.catalog()));
		}

		@Override
		public void onMessage(JsonObject msg) {
			MinecraftServer s = server;
			switch (Protocol.type(msg)) {
				case "welcome" -> {
					botId = Protocol.string(msg, "bot");
					JsonObject character = msg.getAsJsonObject("character");
					characterName = character != null ? Protocol.string(character, "name") : botId;
					String username = Protocol.string(msg, "username");
					SkinData skin = null;
					if (msg.has("skin") && msg.get("skin").isJsonObject()) {
						JsonObject sk = msg.getAsJsonObject("skin");
						skin = new SkinData(Protocol.string(sk, "value"), Protocol.string(sk, "signature"));
					}
					SkinData finalSkin = skin;
					LOGGER.info("Mente pronta. Personagem: {} (usuário {}, skin {})", characterName, username,
							skin != null && skin.isValid() ? "personalizada" : "padrão");
					if (s != null && username != null) {
						s.execute(() -> {
							if (companions != null) {
								companions.configure(username, finalSkin);
							}
						});
					}
				}
				case "chat.say" -> {
					String text = Protocol.string(msg, "text");
					if (s != null) {
						s.execute(() -> say(text));
					}
				}
				case "pong" -> {
					Pending p = pendingPings.remove(Protocol.string(msg, "id"));
					if (p != null) {
						long ms = System.currentTimeMillis() - p.sentAt();
						lastPingMillis = ms;
						if (s != null) {
							s.execute(() -> p.onPong().accept(ms));
						}
					}
				}
				case "skill.start" -> {
					String id = Protocol.string(msg, "id");
					String name = Protocol.string(msg, "skill");
					JsonObject args = msg.has("args") && msg.get("args").isJsonObject() ? msg.getAsJsonObject("args") : null;
					if (s != null) {
						s.execute(() -> startSkill(id, name, args));
					}
				}
				case "skill.cancel" -> {
					if (s != null) {
						s.execute(() -> {
							if (companions != null) {
								companions.stopSkill();
							}
						});
					}
				}
				case "ping" -> mind.send(Protocol.pong(botId, Protocol.string(msg, "id")));
				case "error" -> LOGGER.warn("A Mente reportou um erro: {}", Protocol.string(msg, "message"));
				default -> LOGGER.debug("Mensagem ainda não tratada: {}", Protocol.type(msg));
			}
		}

		@Override
		public void onDisconnected(String reason) {
			LOGGER.warn("Sem conexão com a Mente: {}. Tentando de novo a cada {}s.", reason, config.reconnectSeconds);
		}
	}
}

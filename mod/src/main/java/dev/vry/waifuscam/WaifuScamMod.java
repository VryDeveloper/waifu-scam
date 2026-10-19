package dev.vry.waifuscam;

import com.google.gson.JsonObject;
import dev.vry.waifuscam.bridge.MindClient;
import dev.vry.waifuscam.bridge.Protocol;
import dev.vry.waifuscam.command.WaifuCommand;
import dev.vry.waifuscam.config.ModConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Entrada do mod (Corpo). Fase 0: conecta na Mente quando o servidor sobe,
 * faz o aperto de mão (hello → welcome) e mede o ping pelo comando /waifu ping.
 */
public final class WaifuScamMod implements ModInitializer {
	public static final String MOD_ID = "waifuscam";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private ModConfig config;
	private MinecraftServer server;
	private MindClient mind;

	/** Id e nome da personagem, recebidos no welcome da Mente. */
	private volatile String botId;
	private volatile String characterName;
	private volatile long lastPingMillis = -1;

	private final Map<String, Pending> pendingPings = new ConcurrentHashMap<>();

	private record Pending(long sentAt, LongConsumer onPong) {}

	@Override
	public void onInitialize() {
		config = ModConfig.load(FabricLoader.getInstance().getConfigDir());
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> onServerStopping());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				WaifuCommand.register(dispatcher, this));
		LOGGER.info("Waifu Scam carregado. A Mente é esperada em {}", config.mindUrl);
	}

	private void onServerStarted(MinecraftServer server) {
		this.server = server;
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
		server = null;
	}

	// ---- usado pelo comando ----

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
			mind.send(Protocol.hello("fabric", modVersion, mcVersion));
		}

		@Override
		public void onMessage(JsonObject msg) {
			switch (Protocol.type(msg)) {
				case "welcome" -> {
					botId = Protocol.string(msg, "bot");
					JsonObject character = msg.getAsJsonObject("character");
					characterName = character != null ? Protocol.string(character, "name") : botId;
					LOGGER.info("Mente pronta. Personagem: {}", characterName);
				}
				case "pong" -> {
					Pending p = pendingPings.remove(Protocol.string(msg, "id"));
					if (p != null) {
						long ms = System.currentTimeMillis() - p.sentAt();
						lastPingMillis = ms;
						MinecraftServer s = server;
						if (s != null) {
							s.execute(() -> p.onPong().accept(ms));
						}
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

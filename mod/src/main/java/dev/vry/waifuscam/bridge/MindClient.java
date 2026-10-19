package dev.vry.waifuscam.bridge;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Cliente WebSocket que liga o Corpo (mod) à Mente (Python).
 *
 * Usa o WebSocket nativo do Java, sem dependências. Reconecta sozinho quando a Mente
 * cai ou ainda não subiu. Os callbacks do {@link Handler} rodam em threads de rede:
 * quem mexe no mundo do Minecraft deve repassar o trabalho para a thread do servidor.
 */
public final class MindClient implements WebSocket.Listener {

	public interface Handler {
		void onConnected();

		void onMessage(JsonObject message);

		void onDisconnected(String reason);
	}

	private final URI uri;
	private final Duration reconnectDelay;
	private final Handler handler;
	private final Consumer<String> log;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "waifuscam-mind-reconnect");
		t.setDaemon(true);
		return t;
	});
	private final StringBuilder partial = new StringBuilder();

	private final AtomicBoolean reconnecting = new AtomicBoolean(false);

	private volatile WebSocket socket;
	private volatile boolean closed;
	private volatile boolean warnedOffline;

	public MindClient(URI uri, Duration reconnectDelay, Handler handler, Consumer<String> log) {
		this.uri = uri;
		this.reconnectDelay = reconnectDelay;
		this.handler = handler;
		this.log = log;
	}

	public void start() {
		closed = false;
		connect();
	}

	public boolean isConnected() {
		WebSocket ws = socket;
		return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
	}

	/** Envia uma mensagem já serializada. Devolve false se não houver conexão. */
	public boolean send(String json) {
		WebSocket ws = socket;
		if (ws == null || ws.isOutputClosed()) {
			return false;
		}
		synchronized (this) { // WebSocket não aceita dois envios ao mesmo tempo
			ws.sendText(json, true).join();
		}
		return true;
	}

	public void close() {
		closed = true;
		WebSocket ws = socket;
		socket = null;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "servidor encerrando");
		}
		scheduler.shutdownNow();
	}

	private void connect() {
		if (closed) {
			return;
		}
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.buildAsync(uri, this)
				.whenComplete((ws, err) -> {
					if (err != null) {
						scheduleReconnect("não consegui conectar em " + uri);
					}
				});
	}

	private void scheduleReconnect(String reason) {
		// onClose e onError podem chegar juntos: só um agenda a reconexão
		if (!reconnecting.compareAndSet(false, true)) {
			return;
		}
		boolean wasConnected = socket != null;
		socket = null;
		if (closed) {
			return;
		}
		// avisa uma vez por queda, em vez de a cada tentativa
		if (wasConnected || !warnedOffline) {
			warnedOffline = true;
			handler.onDisconnected(reason);
		}
		scheduler.schedule(() -> {
			reconnecting.set(false);
			connect();
		}, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
	}

	// ---- WebSocket.Listener ----

	@Override
	public void onOpen(WebSocket webSocket) {
		socket = webSocket;
		warnedOffline = false;
		log.accept("Conectado à Mente em " + uri);
		handler.onConnected();
		webSocket.request(1);
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		partial.append(data);
		if (last) {
			String raw = partial.toString();
			partial.setLength(0);
			try {
				handler.onMessage(Protocol.parse(raw));
			} catch (Protocol.ProtocolException e) {
				log.accept("Mensagem inválida da Mente ignorada: " + e.getMessage());
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		scheduleReconnect("Mente desconectou (" + statusCode + ")");
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		scheduleReconnect("erro na conexão: " + error.getMessage());
	}
}

package dev.vry.waifuscam.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * O que os players de verdade andam fazendo, como ela perceberia olhando:
 * quebrando tronco, minerando, construindo com tal bloco, caçando, lutando.
 * Guarda só o último minuto. A Vontade usa isso para ajudar no que ele está
 * fazendo em vez de seguir uma lista.
 */
public final class PlayerWatch {
	private static final int WINDOW_TICKS = 20 * 60;
	private static final int MAX_EVENTS = 200;

	private record Seen(int tick, String kind, String what) {}

	private final Map<UUID, Deque<Seen>> events = new HashMap<>();
	private final MinecraftServer server;
	// Agachar para ela (o "carinho" dos players): viradas de agachado por player
	private final Map<UUID, Deque<Integer>> crouches = new HashMap<>();
	private final Map<UUID, Boolean> wasSneaking = new HashMap<>();
	private final Map<UUID, Integer> lastCrouchEvent = new HashMap<>();
	// Parado: última posição em que ele se mexeu e quando
	private final Map<UUID, net.minecraft.world.phys.Vec3> lastPos = new HashMap<>();
	private final Map<UUID, Integer> lastMoved = new HashMap<>();

	public PlayerWatch(MinecraftServer server) {
		this.server = server;
	}

	private void add(Player p, String kind, String what) {
		if (p instanceof CompanionPlayer || p.level().isClientSide()) {
			return;
		}
		Deque<Seen> q = events.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
		q.addLast(new Seen(server.getTickCount(), kind, what.replace("minecraft:", "")));
		while (q.size() > MAX_EVENTS) {
			q.removeFirst();
		}
	}

	public void broke(Player p, BlockState state) {
		add(p, "broke", Stacks.id(state.getBlock()));
	}

	/** Clicou num bloco com um bloco na mão: está construindo com ele. */
	public void used(Player p, ItemStack held) {
		if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
			add(p, "placed", Stacks.id(held));
		}
	}

	public void attacked(Player p, Entity target) {
		add(p, "attacked", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath());
	}

	/**
	 * Chamado a cada tick: quem está agachando e levantando perto dela, olhando
	 * para ela. Devolve os nomes de quem fez isso agora (3 vezes em 2 s).
	 */
	public java.util.List<String> crouching(ServerPlayer me) {
		java.util.List<String> out = new java.util.ArrayList<>();
		int now = server.getTickCount();
		for (ServerPlayer p : me.level().players()) {
			if (p == me || p instanceof CompanionPlayer || p.distanceTo(me) > 6) {
				continue;
			}
			boolean sneaking = p.isShiftKeyDown();
			boolean before = wasSneaking.getOrDefault(p.getUUID(), false);
			wasSneaking.put(p.getUUID(), sneaking);
			if (!sneaking || before) {
				continue;
			}
			Deque<Integer> q = crouches.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
			q.addLast(now);
			while (!q.isEmpty() && now - q.peekFirst() > 40) {
				q.removeFirst();
			}
			boolean facing = dev.vry.waifuscam.control.Controls.aimed(p, me.getEyePosition(), 35);
			if (q.size() >= 3 && facing && now - lastCrouchEvent.getOrDefault(p.getUUID(), -1000) > 200) {
				lastCrouchEvent.put(p.getUUID(), now);
				q.clear();
				out.add(p.getGameProfile().name());
			}
		}
		return out;
	}

	/** Resumo do último minuto de cada player perto dela, para o state. */
	public JsonArray summary(ServerPlayer me, double radius) {
		JsonArray out = new JsonArray();
		int now = server.getTickCount();
		for (ServerPlayer p : me.level().players()) {
			if (p == me || p instanceof CompanionPlayer || p.isSpectator() || p.distanceTo(me) > radius) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("name", p.getGameProfile().name());
			o.addProperty("distance", Math.round(p.distanceTo(me) * 10) / 10.0);
			o.addProperty("underground", !p.level().canSeeSky(p.blockPosition()) && p.getY() < 60);
			o.addProperty("y", p.getBlockY());
			o.addProperty("sleeping", p.isSleeping());
			var pos = p.position();
			var before = lastPos.get(p.getUUID());
			if (before == null || before.distanceTo(pos) > 1.0) {
				lastPos.put(p.getUUID(), pos);
				lastMoved.put(p.getUUID(), now);
			}
			o.addProperty("idle_seconds", (now - lastMoved.getOrDefault(p.getUUID(), now)) / 20);
			Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
			Deque<Seen> q = events.get(p.getUUID());
			if (q != null) {
				q.removeIf(s -> now - s.tick() > WINDOW_TICKS);
				for (Seen s : q) {
					counts.computeIfAbsent(s.kind(), k -> new LinkedHashMap<>()).merge(s.what(), 1, Integer::sum);
				}
			}
			for (String kind : new String[] {"broke", "placed", "attacked"}) {
				JsonObject c = new JsonObject();
				counts.getOrDefault(kind, Map.of()).entrySet().stream()
						.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
						.limit(6)
						.forEach(e -> c.addProperty(e.getKey(), e.getValue()));
				o.add(kind, c);
			}
			out.add(o);
		}
		return out;
	}

	public void forget(UUID player) {
		events.remove(player);
	}
}

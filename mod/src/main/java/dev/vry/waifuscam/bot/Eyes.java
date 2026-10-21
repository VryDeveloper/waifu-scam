package dev.vry.waifuscam.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vry.waifuscam.hands.Combat;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Os olhos dela. Uma vez por segundo ela "olha" para onde a cabeça está virada:
 * lança raios num cone de visão (como a tela de um player) e anota o que bateu.
 *
 * - Só vê o que tem linha de visão: minério atrás da pedra continua escondido.
 * - No escuro só enxerga de perto.
 * - Coisas que valem lembrar (minérios, baús, spawner, sino de vila...) viram
 *   lembrança: posição e quando viu. A Mente guarda isso no disco e devolve ao
 *   conectar, então ela lembra entre uma sessão e outra.
 */
public final class Eyes {
	private static final double RANGE = 48;
	private static final double ENTITY_RANGE = 40;
	private static final double FOV_HALF_YAW = 55;
	private static final double FOV_HALF_PITCH = 38;
	private static final int COLS = 13;
	private static final int ROWS = 9;
	private static final int DARK_SIGHT = 4; // no escuro, só enxerga até aqui
	private static final int MAX_PER_BLOCK = 16;
	private static final int CLUSTER = 3; // minérios a até 3 blocos contam como o mesmo veio

	/** Um lugar lembrado: onde viu e quando (tick do servidor). */
	public record Spot(BlockPos pos, String dimension, long seenAt) {}

	/** O que ela vê agora (o último olhar). */
	private final Map<String, Seen> blocksInView = new LinkedHashMap<>();
	private final List<JsonObject> entitiesInView = new ArrayList<>();
	private String lookingAt;
	private String biome;

	private final Map<String, List<Spot>> memory = new HashMap<>();
	private final Queue<JsonObject> news = new ArrayDeque<>();
	private final Queue<JsonObject> gone = new ArrayDeque<>();

	private static final class Seen {
		final String block;
		double distance;
		String dir;
		int count;

		Seen(String block) {
			this.block = block;
		}
	}

	// ---- o que vale notar ----------------------------------------------------------

	/** Blocos que ela nota quando vê (aparecem em "seeing"). */
	static boolean notable(String id) {
		String b = id.replace("minecraft:", "");
		return b.endsWith("_ore") || b.equals("ancient_debris") || b.endsWith("chest") || b.equals("barrel")
				|| b.equals("spawner") || b.equals("trial_spawner") || b.equals("bell") || b.equals("lava")
				|| b.equals("crafting_table") || b.equals("furnace") || b.endsWith("_bed") || b.equals("obsidian")
				|| b.equals("budding_amethyst") || b.equals("amethyst_cluster") || b.equals("nether_portal")
				|| b.equals("end_portal_frame") || b.equals("enchanting_table") || b.equals("anvil")
				|| b.equals("beehive") || b.equals("bee_nest") || b.equals("melon") || b.equals("pumpkin")
				|| b.equals("sugar_cane") || b.endsWith("wheat");
	}

	/** Dos notados, o que vale lembrar onde fica. */
	static boolean memorable(String id) {
		String b = id.replace("minecraft:", "");
		return b.endsWith("_ore") || b.equals("ancient_debris") || b.equals("chest") || b.equals("barrel")
				|| b.equals("spawner") || b.equals("trial_spawner") || b.equals("bell") || b.equals("obsidian")
				|| b.equals("budding_amethyst") || b.equals("nether_portal") || b.equals("end_portal_frame")
				|| b.equals("enchanting_table") || b.equals("melon") || b.equals("pumpkin") || b.equals("sugar_cane")
				|| b.equals("bee_nest");
	}

	/** Achado que dá vontade de comentar. */
	static boolean rare(String id) {
		String b = id.replace("minecraft:", "");
		return b.contains("diamond") || b.contains("emerald") || b.equals("ancient_debris") || b.contains("spawner")
				|| b.equals("bell") || b.equals("end_portal_frame") || b.equals("budding_amethyst");
	}

	// ---- olhar -----------------------------------------------------------------------

	/** Chamado a cada segundo com ela viva no mundo. */
	public void look(ServerPlayer me, long tick) {
		ServerLevel level = me.level();
		String dim = level.dimension().identifier().toString();
		Vec3 eye = me.getEyePosition();
		float yaw = me.getYHeadRot();
		float pitch = me.getXRot();
		blocksInView.clear();
		entitiesInView.clear();
		lookingAt = null;

		var rnd = ThreadLocalRandom.current();
		double stepYaw = 2 * FOV_HALF_YAW / (COLS - 1);
		double stepPitch = 2 * FOV_HALF_PITCH / (ROWS - 1);
		for (int c = 0; c < COLS; c++) {
			for (int r = 0; r < ROWS; r++) {
				// Um tremidinho a cada olhar: com o tempo, cobre o campo de visão inteiro.
				double dy = -FOV_HALF_YAW + c * stepYaw + rnd.nextDouble(-stepYaw / 2, stepYaw / 2);
				double dp = -FOV_HALF_PITCH + r * stepPitch + rnd.nextDouble(-stepPitch / 2, stepPitch / 2);
				ray(me, level, dim, eye, yaw + dy, Mth.clamp(pitch + dp, -90, 90), tick, false);
			}
		}
		ray(me, level, dim, eye, yaw, pitch, tick, true); // o centro: para onde ela está olhando

		// Bichos, players e itens no chão que estão no campo de visão e à vista.
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		List<Entity> around = level.getEntities(me, me.getBoundingBox().inflate(ENTITY_RANGE),
				e -> (e instanceof LivingEntity l && l.isAlive() && !e.isSpectator()) || e instanceof ItemEntity);
		around.sort(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
		double bestCenter = Math.cos(Math.toRadians(7));
		for (Entity e : around) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double dist = to.length();
			if (dist > ENTITY_RANGE || dist < 0.01) {
				continue;
			}
			double cos = to.normalize().dot(look);
			if (cos < Math.cos(Math.toRadians(FOV_HALF_YAW + 5)) || !me.hasLineOfSight(e)) {
				continue;
			}
			if (dist > DARK_SIGHT * 2 && level.getMaxLocalRawBrightness(e.blockPosition()) < 2 && !(e instanceof ServerPlayer)) {
				continue; // no breu não dá para ver bicho longe
			}
			if (entitiesInView.size() < 10) {
				JsonObject o = new JsonObject();
				o.addProperty("type", type(e));
				String name = name(e);
				if (name != null) {
					o.addProperty("name", name);
				}
				o.addProperty("distance", Math.round(dist * 10) / 10.0);
				o.addProperty("dir", direction(me, e.position()));
				if (e instanceof LivingEntity l && Combat.isHostile(l, me)) {
					o.addProperty("hostile", true);
				}
				entitiesInView.add(o);
			}
			if (cos > bestCenter) {
				bestCenter = cos;
				String name = name(e);
				lookingAt = name != null ? name : type(e);
			}
		}

		var key = level.getBiome(me.blockPosition()).unwrapKey();
		biome = key.map(k -> k.identifier().getPath()).orElse(null);
		forgetMissingAround(level, me.blockPosition(), dim);
	}

	private void ray(ServerPlayer me, ServerLevel level, String dim, Vec3 eye, double yaw, double pitch, long tick, boolean center) {
		Vec3 dir = Vec3.directionFromRotation((float) pitch, (float) yaw);
		Vec3 end = eye.add(dir.scale(RANGE));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, me));
		// Água é transparente para os olhos: olha através dela.
		if (hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
			hit = level.clip(new ClipContext(hit.getLocation().add(dir.scale(0.05)), end, ClipContext.Block.VISUAL,
					ClipContext.Fluid.NONE, me));
		}
		if (hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		double dist = hit.getLocation().distanceTo(eye);
		BlockPos face = pos.relative(hit.getDirection());
		if (dist > DARK_SIGHT && level.getMaxLocalRawBrightness(face) < 3) {
			return; // escuro demais para ver o que é
		}
		BlockState state = level.getBlockState(pos);
		String id = level.getFluidState(pos).is(FluidTags.LAVA) ? "minecraft:lava" : Stacks.id(state.getBlock());
		if (center && lookingAt == null && dist < 24) {
			lookingAt = id.replace("minecraft:", "");
		}
		if (!notable(id)) {
			return;
		}
		Seen s = blocksInView.computeIfAbsent(id, Seen::new);
		s.count++;
		if (s.dir == null || dist < s.distance) {
			s.distance = dist;
			s.dir = direction(me, Vec3.atCenterOf(pos));
		}
		if (memorable(id)) {
			remember(id, pos, dim, tick, true);
		}
	}

	// ---- lembrar ---------------------------------------------------------------------

	private void remember(String block, BlockPos pos, String dim, long tick, boolean report) {
		List<Spot> spots = memory.computeIfAbsent(block, k -> new ArrayList<>());
		for (int i = 0; i < spots.size(); i++) {
			Spot s = spots.get(i);
			if (s.dimension().equals(dim) && s.pos().distManhattan(pos) <= CLUSTER) {
				spots.set(i, new Spot(s.pos(), dim, tick)); // mesmo veio: só atualiza quando viu
				return;
			}
		}
		spots.add(new Spot(pos.immutable(), dim, tick));
		if (spots.size() > MAX_PER_BLOCK) {
			spots.sort(Comparator.comparingLong(Spot::seenAt));
			spots.remove(0);
		}
		if (report) {
			JsonObject o = spotJson(block, pos, dim);
			if (rare(block)) {
				o.addProperty("rare", true);
			}
			news.add(o);
		}
	}

	/** Lembranças que vieram da Mente (sessões anteriores). */
	public void seed(JsonArray blocks) {
		memory.clear();
		for (var el : blocks) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			try {
				String block = o.get("block").getAsString();
				if (!block.contains(":")) {
					block = "minecraft:" + block;
				}
				BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				String dim = o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld";
				remember(block, pos, dim, 0, false);
			} catch (RuntimeException ignored) {
				// lembrança estragada: deixa para lá
			}
		}
	}

	/** Lembranças perto dela que já não estão lá (alguém minerou, ela mesma pegou). */
	private void forgetMissingAround(ServerLevel level, BlockPos me, String dim) {
		for (var entry : memory.entrySet()) {
			entry.getValue().removeIf(s -> {
				if (!s.dimension().equals(dim) || s.pos().distManhattan(me) > 24 || !level.isLoaded(s.pos())) {
					return false;
				}
				if (Stacks.id(level.getBlockState(s.pos()).getBlock()).equals(entry.getKey())) {
					return false;
				}
				gone.add(spotJson(entry.getKey(), s.pos(), dim));
				return true;
			});
		}
	}

	/**
	 * O lugar lembrado mais perto com um desses blocos, a até maxDist. Lembranças
	 * em chunks carregados são conferidas (se sumiu, esquece).
	 */
	public BlockPos nearest(ServerLevel level, BlockPos from, Collection<String> blocks, int maxDist, Set<BlockPos> skip) {
		String dim = level.dimension().identifier().toString();
		BlockPos best = null;
		double bestD = (double) maxDist * maxDist;
		for (String block : blocks) {
			List<Spot> spots = memory.get(block);
			if (spots == null) {
				continue;
			}
			for (var it = spots.iterator(); it.hasNext(); ) {
				Spot s = it.next();
				if (!s.dimension().equals(dim) || skip.contains(s.pos())) {
					continue;
				}
				if (level.isLoaded(s.pos()) && !Stacks.id(level.getBlockState(s.pos()).getBlock()).equals(block)) {
					it.remove();
					gone.add(spotJson(block, s.pos(), dim));
					continue;
				}
				double d = s.pos().distSqr(from);
				if (d < bestD) {
					bestD = d;
					best = s.pos();
				}
			}
		}
		return best;
	}

	public void forget(String block, BlockPos pos, String dim) {
		List<Spot> spots = memory.get(block);
		if (spots != null && spots.removeIf(s -> s.pos().equals(pos) && s.dimension().equals(dim))) {
			gone.add(spotJson(block, pos, dim));
		}
	}

	// ---- para a Mente ---------------------------------------------------------------

	/** O que ela está vendo agora, para o state. */
	public JsonObject seeing() {
		JsonObject o = new JsonObject();
		if (lookingAt != null) {
			o.addProperty("looking_at", lookingAt);
		}
		if (biome != null) {
			o.addProperty("biome", biome);
		}
		JsonArray entities = new JsonArray();
		entitiesInView.forEach(entities::add);
		o.add("entities", entities);
		JsonArray blocks = new JsonArray();
		blocksInView.values().stream()
				.sorted(Comparator.comparingDouble(s -> s.distance))
				.limit(10)
				.forEach(s -> {
					JsonObject b = new JsonObject();
					b.addProperty("block", s.block.replace("minecraft:", ""));
					b.addProperty("distance", Math.round(s.distance * 10) / 10.0);
					b.addProperty("dir", s.dir);
					b.addProperty("count", s.count);
					blocks.add(b);
				});
		o.add("blocks", blocks);
		return o;
	}

	/** Achados novos desde a última vez (cada um vira um evento "seen"). */
	public List<JsonObject> drainNews() {
		List<JsonObject> out = new ArrayList<>(news);
		news.clear();
		return out;
	}

	/** Lembranças que deixaram de valer (evento "gone"). */
	public List<JsonObject> drainGone() {
		List<JsonObject> out = new ArrayList<>(gone);
		gone.clear();
		return out;
	}

	private static JsonObject spotJson(String block, BlockPos pos, String dim) {
		JsonObject o = new JsonObject();
		o.addProperty("block", block.replace("minecraft:", ""));
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		o.addProperty("dimension", dim);
		return o;
	}

	// ---- ajudantes -------------------------------------------------------------------

	static String type(Entity e) {
		if (e instanceof ItemEntity item) {
			return "item:" + Stacks.id(item.getItem()).replace("minecraft:", "");
		}
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	static String name(Entity e) {
		if (e instanceof ServerPlayer p) {
			return p.getGameProfile().name();
		}
		return e.hasCustomName() && e.getCustomName() != null ? e.getCustomName().getString() : null;
	}

	/** Onde fica, em relação a para onde ela está virada. */
	public static String direction(ServerPlayer me, Vec3 target) {
		Vec3 d = target.subtract(me.getEyePosition());
		double targetYaw = Math.toDegrees(Math.atan2(-d.x, d.z));
		double delta = Mth.wrapDegrees(targetYaw - me.getYHeadRot());
		String side;
		if (Math.abs(delta) <= 25) {
			side = "na frente";
		} else if (Math.abs(delta) >= 135) {
			side = "atrás";
		} else if (delta > 0) {
			side = Math.abs(delta) < 70 ? "na frente, à direita" : "à direita";
		} else {
			side = Math.abs(delta) < 70 ? "na frente, à esquerda" : "à esquerda";
		}
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		if (d.y > 3 && d.y > horizontal * 0.6) {
			side += ", em cima";
		} else if (d.y < -3 && -d.y > horizontal * 0.6) {
			side += ", embaixo";
		}
		return side;
	}
}

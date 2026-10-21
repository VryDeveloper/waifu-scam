package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.hands.Tools;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ajudar a minerar: quebra todos os minérios à vista por perto (carvão, ferro,
 * cobre, ouro, redstone, lápis, diamante...), um por um, pega o que caiu e segue
 * o veio — quebrar um minério deixa os vizinhos à vista, então ela continua até
 * acabar. Não cava túnel atrás de minério escondido: isso é o "collect".
 */
public final class MineAroundSkill implements Skill {
	private static final int RADIUS = 10;
	private static final int TIMEOUT_TICKS = 20 * 60 * 4;
	private static final int MAX_ORES = 40;

	private BlockPos center;
	private BlockPos target;
	private Skill walk;
	private Digger digger;
	private BlockPos lastBroken;
	private int pickupTicks = -1;
	private int mined;
	private int ticks;
	private int wait;
	private final Set<BlockPos> skip = new HashSet<>();
	private String failure;

	@Override
	public String name() {
		return "mine_ores";
	}

	@Override
	public String describe() {
		return "minerando os minérios por perto";
	}

	static boolean isOre(BlockState s) {
		String id = Stacks.id(s.getBlock());
		return id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		if (center == null) {
			center = me.blockPosition();
		}
		if (++ticks > TIMEOUT_TICKS || mined >= MAX_ORES) {
			return mined > 0 ? Status.SUCCEEDED : fail("demorei demais procurando minério");
		}
		if (wait-- > 0) {
			return Status.RUNNING;
		}
		if (digger != null) {
			Digger.Status s = digger.tick(me, ctx.controls());
			if (s == Digger.Status.DIGGING) {
				return Status.RUNNING;
			}
			if (s == Digger.Status.DONE) {
				mined++;
				lastBroken = digger.pos();
				pickupTicks = 0;
			} else {
				skip.add(digger.pos());
			}
			digger = null;
			target = null;
			return Status.RUNNING;
		}
		if (pickupTicks >= 0) {
			if (pickup(ctx)) {
				pickupTicks = -1;
				walk = null;
				wait = Human.pause();
			}
			return Status.RUNNING;
		}
		if (target == null || !isOre(level.getBlockState(target))) {
			target = next(me, level);
			walk = null;
			if (target == null) {
				return mined > 0 ? Status.SUCCEEDED : fail("não vi minério à vista por aqui");
			}
		}
		if (me.isWithinBlockInteractionRange(target, 0)) {
			digger = new Digger(level, target);
			return Status.RUNNING;
		}
		if (walk == null) {
			walk = new GotoSkill(LevelTerrain.of(target), 3.0, "minério");
		}
		Status s = walk.tick(ctx);
		if (s == Status.FAILED || (s == Status.SUCCEEDED && !me.isWithinBlockInteractionRange(target, 0.5))) {
			skip.add(target);
			target = null;
		}
		return Status.RUNNING;
	}

	/** O minério à vista mais perto (perto de onde começou ou de onde ela está). */
	private BlockPos next(ServerPlayer me, ServerLevel level) {
		List<BlockPos> found = BlockFinder.find(level, me.blockPosition(), RADIUS, 16, p -> {
			if (skip.contains(p.immutable()) || p.distManhattan(center) > RADIUS * 2) {
				return false;
			}
			BlockState s = level.getBlockState(p);
			return isOre(s) && BlockFinder.exposed(level, p) && Tools.canHarvest(me, s)
					&& !(BlockFinder.nextToFluid(level, p) && lavaAround(level, p));
		});
		// Veio primeiro: o vizinho do que acabou de quebrar.
		if (lastBroken != null) {
			Optional<BlockPos> vein = found.stream().filter(p -> p.distManhattan(lastBroken) <= 2).findFirst();
			if (vein.isPresent()) {
				return vein.get();
			}
		}
		return found.isEmpty() ? null : found.get(0);
	}

	private static boolean lavaAround(ServerLevel level, BlockPos p) {
		for (var d : net.minecraft.core.Direction.values()) {
			if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	private boolean pickup(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (++pickupTicks < 5) {
			return false;
		}
		BlockPos around = lastBroken != null ? lastBroken : me.blockPosition();
		Optional<ItemEntity> drop = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(4),
						e -> e.isAlive() && !e.getItem().isEmpty())
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
		if (drop.isEmpty() || pickupTicks > 20 * 6) {
			return true;
		}
		Vec3 at = drop.get().position();
		if (walk == null || pickupTicks % 20 == 0) {
			walk = new GotoSkill(new Pos((int) Math.floor(at.x), (int) Math.floor(at.y + 0.2), (int) Math.floor(at.z)), 0.6, "item");
		}
		return walk.tick(ctx) == Status.FAILED;
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.Knowledge.Source;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.hands.Tools;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minerar até ter mais N itens de uma família (troncos, pedregulho, ferro bruto).
 *
 * Procura o bloco certo mais perto que esteja à vista, anda até ele, quebra com a
 * melhor ferramenta e pega o que caiu. Sem nenhum à vista (pedra fica embaixo da
 * terra), cava uma escada para baixo até achar.
 */
public final class CollectSkill implements Skill {
	private static final int[] RADII = {16, 32, 48};
	private static final int TIMEOUT_TICKS = 20 * 60 * 5;
	private static final int MAX_DIG_DOWN_STEPS = 40;

	private enum Phase { SEARCH, WALK, DIG, PICKUP, DIG_DOWN }

	private final Set<String> items;
	private final Set<String> blocks;
	private final boolean underground;
	private final String label;
	private final int wanted;
	private int goal = -1;

	private Phase phase = Phase.SEARCH;
	private final Set<BlockPos> unreachable = new HashSet<>();
	private BlockPos target;
	private Skill walk;
	private Digger digger;
	private BlockPos lastBroken;
	private int pickupTicks;
	private int ticks;
	private int digDownSteps;
	private Direction digDirection;
	private final List<BlockPos> digQueue = new ArrayList<>();
	private String failure;

	public CollectSkill(Source source, int count) {
		this(source.items(), source.blocks(), count, Names.pretty(source.items().iterator().next()));
	}

	public CollectSkill(Set<String> items, Set<String> blocks, int count, String label) {
		this.items = items;
		this.blocks = blocks;
		this.wanted = Math.max(1, count);
		this.label = label;
		// Pedra, minérios e afins ficam embaixo da terra: vale cavar até eles.
		this.underground = blocks.stream().anyMatch(b -> b.contains("stone") || b.contains("ore")
				|| b.contains("deepslate") || b.endsWith("tuff") || b.endsWith("andesite") || b.endsWith("diorite")
				|| b.endsWith("granite"));
	}

	@Override
	public String name() {
		return "collect";
	}

	@Override
	public String describe() {
		return "pegando " + label;
	}

	private int have(SkillContext ctx) {
		return Stacks.count(ctx.player().getInventory(), s -> items.contains(Stacks.id(s)));
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (goal < 0) {
			goal = have(ctx) + wanted;
		}
		if (have(ctx) >= goal) {
			return Status.SUCCEEDED;
		}
		if (++ticks > TIMEOUT_TICKS) {
			return fail("demorei demais procurando " + label);
		}
		ServerLevel level = ctx.level();

		switch (phase) {
			case SEARCH -> search(ctx);
			case WALK -> {
				Status s = walk.tick(ctx);
				if (target != null && level.getBlockState(target).isAir()) {
					phase = Phase.SEARCH; // alguém quebrou antes
				} else if (target != null && ctx.player().isWithinBlockInteractionRange(target, 0)) {
					startDigging(ctx, target);
				} else if (s == Status.FAILED) {
					unreachable.add(target);
					phase = Phase.SEARCH;
				} else if (s == Status.SUCCEEDED) {
					if (ctx.player().isWithinBlockInteractionRange(target, 0.5)) {
						startDigging(ctx, target);
					} else {
						unreachable.add(target);
						phase = Phase.SEARCH;
					}
				}
			}
			case DIG -> {
				Digger.Status s = digger.tick(ctx.player(), ctx.controls());
				if (s == Digger.Status.DONE) {
					lastBroken = digger.pos();
					digger = null;
					pickupTicks = 0;
					phase = Phase.PICKUP;
				} else if (s == Digger.Status.FAILED) {
					unreachable.add(digger.pos());
					String why = digger.failure();
					digger = null;
					if (why != null && why.contains("ferramenta")) {
						return fail("não tenho ferramenta para pegar " + label);
					}
					phase = Phase.SEARCH;
				}
			}
			case PICKUP -> pickup(ctx);
			case DIG_DOWN -> {
				return digDown(ctx);
			}
		}
		return failure == null ? Status.RUNNING : Status.FAILED;
	}

	private void startDigging(SkillContext ctx, BlockPos pos) {
		digger = new Digger(ctx.level(), pos);
		phase = Phase.DIG;
		walk = null;
	}

	// ---- achar o bloco -----------------------------------------------------------

	private boolean wantedBlock(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.isAir() || !blocks.contains(Stacks.id(s.getBlock()))) {
			return false;
		}
		if (s.getDestroySpeed(level, p) < 0) {
			return false; // inquebrável
		}
		return true;
	}

	private void search(SkillContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos feet = ctx.player().blockPosition();
		for (int radius : RADII) {
			List<BlockPos> found = BlockFinder.find(level, feet, radius, 24, p -> wantedBlock(level, p)
					&& !unreachable.contains(p.immutable())
					&& BlockFinder.exposed(level, p)
					&& !p.equals(feet.below())
					&& (!level.getBlockState(p).is(BlockTags.LOGS) || BlockFinder.naturalTree(level, p)));
			found.removeIf(p -> !Tools.canHarvest(ctx.player(), level.getBlockState(p))
					|| BlockFinder.nextToFluid(level, p) && hasLava(level, p));
			if (!found.isEmpty()) {
				target = found.get(0);
				if (ctx.player().isWithinBlockInteractionRange(target, 0)) {
					startDigging(ctx, target);
				} else {
					walk = new GotoSkill(LevelTerrain.of(target), 3.0, label);
					phase = Phase.WALK;
				}
				return;
			}
			if (underground && radius >= 16) {
				break; // não adianta procurar longe: cava aqui mesmo
			}
		}
		if (underground) {
			phase = Phase.DIG_DOWN;
			digDirection = ctx.player().getDirection();
			return;
		}
		failure = "não achei " + label + " por perto";
	}

	private static boolean hasLava(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.values()) {
			if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	// ---- pegar o drop ------------------------------------------------------------

	private void pickup(SkillContext ctx) {
		pickupTicks++;
		if (pickupTicks < 5) {
			return; // o item ainda está nascendo
		}
		BlockPos around = lastBroken != null ? lastBroken : ctx.player().blockPosition();
		List<ItemEntity> drops = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(5),
				e -> e.isAlive() && !e.getItem().isEmpty());
		if (drops.isEmpty() || pickupTicks > 20 * 8) {
			phase = Phase.SEARCH;
			walk = null;
			return;
		}
		drops.sort(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())));
		ItemEntity next = drops.get(0);
		Vec3 at = next.position();
		if (walk == null || pickupTicks % 20 == 0) {
			walk = new GotoSkill(new Pos((int) Math.floor(at.x), (int) Math.floor(at.y + 0.2), (int) Math.floor(at.z)), 0.6, "item");
		}
		Status s = walk.tick(ctx);
		if (s == Status.FAILED) {
			next.setPickUpDelay(0);
			phase = Phase.SEARCH; // não dá para chegar: deixa para lá
			walk = null;
		}
	}

	// ---- cavar para baixo ----------------------------------------------------------

	/**
	 * Escada de 1 de largura: quebra frente-cabeça, frente-pés e frente-embaixo,
	 * anda um degrau para baixo e repete. Desvia de água e lava.
	 */
	private Status digDown(SkillContext ctx) {
		ServerLevel level = ctx.level();
		if (digger != null) {
			Digger.Status s = digger.tick(ctx.player(), ctx.controls());
			if (s == Digger.Status.FAILED) {
				return fail("não consegui cavar: " + digger.failure());
			}
			if (s == Digger.Status.DONE) {
				lastBroken = digger.pos();
				digger = null;
			}
			return Status.RUNNING;
		}
		if (!digQueue.isEmpty()) {
			BlockPos next = digQueue.remove(0);
			BlockState s = level.getBlockState(next);
			if (!s.isAir() && !s.getCollisionShape(level, next).isEmpty()) {
				if (s.getDestroySpeed(level, next) < 0) {
					return fail("bati em algo que não quebra");
				}
				digger = new Digger(level, next);
			}
			return Status.RUNNING;
		}
		if (walk != null) {
			Status s = walk.tick(ctx);
			if (s == Status.RUNNING) {
				return Status.RUNNING;
			}
			walk = null;
			// Achou o que queria à vista? Volta a procurar normal.
			List<BlockPos> near = BlockFinder.find(level, ctx.player().blockPosition(), 5, 1,
					p -> wantedBlock(level, p) && BlockFinder.exposed(level, p) && Tools.canHarvest(ctx.player(), level.getBlockState(p)));
			if (!near.isEmpty()) {
				phase = Phase.SEARCH;
				return Status.RUNNING;
			}
		}
		if (++digDownSteps > MAX_DIG_DOWN_STEPS || ctx.player().getBlockY() <= level.getMinY() + 6) {
			return fail("cavei bastante e não achei " + label);
		}
		BlockPos feet = ctx.player().blockPosition();
		for (int turn = 0; turn < 4; turn++) {
			BlockPos front = feet.relative(digDirection);
			List<BlockPos> step = List.of(front.above(), front, front.below());
			boolean safe = true;
			for (BlockPos b : step) {
				if (BlockFinder.nextToFluid(level, b) || !level.getFluidState(b).isEmpty()) {
					safe = false;
					break;
				}
			}
			// Não cava para dentro de um buraco fundo.
			if (safe && level.getBlockState(front.below(2)).isAir() && level.getBlockState(front.below(3)).isAir()) {
				safe = false;
			}
			if (safe) {
				digQueue.addAll(step);
				walk = new GotoSkill(LevelTerrain.of(front.below()), 0.5, "degrau");
				return Status.RUNNING;
			}
			digDirection = digDirection.getClockWise();
		}
		return fail("só tem água ou lava por aqui, não dá para cavar");
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

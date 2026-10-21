package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cuidar da plantação como um player: colhe o que está maduro, replanta na
 * hora, planta semente na terra arada vazia e, com enxada e semente na mochila,
 * ara terra perto da água para aumentar a horta (até 9 blocos por vez).
 *
 * Nunca colhe o que ainda está verde.
 */
public final class FarmSkill implements Skill {
	private static final int RADIUS = 16;
	private static final int MAX_TILL = 9;
	private static final int TIMEOUT_TICKS = 20 * 60 * 5;
	/** Sementes que ela sabe plantar, da que mais vale para a que menos vale. */
	private static final List<Item> SEEDS = List.of(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS);

	private enum Kind { HARVEST, PLANT, TILL }

	private record Task(Kind kind, BlockPos pos) {}

	private Task task;
	private Skill walk;
	private Digger digger;
	private BlockPos lastHarvest;
	private Item replant;
	private int pickupTicks = -1;
	private int pause;
	private int ticks;
	private int tilled;
	private int done;
	private final Set<BlockPos> skip = new HashSet<>();
	private String failure;

	@Override
	public String name() {
		return "farm";
	}

	@Override
	public String describe() {
		return "cuidando da plantação";
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		if (++ticks > TIMEOUT_TICKS) {
			return done > 0 ? Status.SUCCEEDED : fail("demorei demais na plantação");
		}
		if (pause-- > 0) {
			return Status.RUNNING;
		}
		if (digger != null) {
			Digger.Status s = digger.tick(me, ctx.controls());
			if (s == Digger.Status.DIGGING) {
				return Status.RUNNING;
			}
			digger = null;
			if (s == Digger.Status.DONE) {
				done++;
				pickupTicks = 0;
			} else {
				skip.add(task.pos());
			}
			task = null;
			return Status.RUNNING;
		}
		if (pickupTicks >= 0) {
			if (pickup(ctx)) {
				pickupTicks = -1;
				walk = null;
				replant(me, level);
				pause = Human.pause();
			}
			return Status.RUNNING;
		}
		if (task == null) {
			task = next(me, level);
			walk = null;
			if (task == null) {
				return done > 0 ? Status.SUCCEEDED
						: fail(SEEDS.stream().anyMatch(s -> has(me, s))
								? "não tem plantação madura nem terra para plantar por perto"
								: "não tem plantação madura por perto, e estou sem sementes");
			}
		}
		if (!me.isWithinBlockInteractionRange(task.pos(), 0)) {
			if (walk == null) {
				walk = new GotoSkill(LevelTerrain.of(task.pos()), 2.5, "plantação");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				skip.add(task.pos());
				task = null;
			}
			return Status.RUNNING;
		}
		walk = null;
		act(ctx, task);
		return Status.RUNNING;
	}

	private void act(SkillContext ctx, Task t) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		switch (t.kind()) {
			case HARVEST -> {
				BlockState s = level.getBlockState(t.pos());
				replant = s.getBlock().asItem();
				lastHarvest = t.pos();
				digger = new Digger(level, t.pos(), false);
				return; // a quebra continua nos próximos ticks
			}
			case PLANT -> {
				Item seed = bestSeed(me);
				if (seed != null && Placer.place(me, seed, t.pos().above())) {
					done++;
				} else {
					skip.add(t.pos());
				}
			}
			case TILL -> {
				if (useHoe(me, ctx, t.pos())) {
					tilled++;
					done++;
				} else {
					skip.add(t.pos());
				}
			}
		}
		task = null;
		pause = Human.reaction();
	}

	// ---- escolher o que fazer ------------------------------------------------------

	private Task next(ServerPlayer me, ServerLevel level) {
		BlockPos feet = me.blockPosition();
		// 1) colher o que está maduro
		List<BlockPos> ripe = BlockFinder.find(level, feet, RADIUS, 1, p -> !skip.contains(p.immutable())
				&& level.getBlockState(p).getBlock() instanceof CropBlock crop && crop.isMaxAge(level.getBlockState(p)));
		if (!ripe.isEmpty()) {
			return new Task(Kind.HARVEST, ripe.get(0));
		}
		if (bestSeed(me) == null) {
			return null;
		}
		// 2) plantar na terra arada vazia
		List<BlockPos> empty = BlockFinder.find(level, feet, RADIUS, 1, p -> !skip.contains(p.immutable())
				&& level.getBlockState(p).is(Blocks.FARMLAND) && level.getBlockState(p.above()).isAir());
		if (!empty.isEmpty()) {
			return new Task(Kind.PLANT, empty.get(0));
		}
		// 3) arar terra perto da água (com enxada)
		if (tilled >= MAX_TILL || Stacks.find(me.getInventory(), s -> s.is(ItemTags.HOES)) < 0) {
			return null;
		}
		List<BlockPos> water = BlockFinder.find(level, feet, 12, 4, p -> level.getFluidState(p).is(FluidTags.WATER)
				&& level.getFluidState(p).isSource());
		for (BlockPos w : water) {
			List<BlockPos> dirt = BlockFinder.find(level, w, 4, 1, p -> p.getY() == w.getY()
					&& !skip.contains(p.immutable()) && tillable(level, p) && !p.equals(feet.below()));
			if (!dirt.isEmpty()) {
				return new Task(Kind.TILL, dirt.get(0));
			}
		}
		return null;
	}

	private static boolean tillable(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT))
				&& level.getBlockState(p.above()).isAir();
	}

	// ---- mãos ------------------------------------------------------------------------

	private static boolean has(ServerPlayer me, Item item) {
		return Stacks.find(me.getInventory(), s -> s.is(item)) >= 0;
	}

	private static Item bestSeed(ServerPlayer me) {
		for (Item seed : SEEDS) {
			if (has(me, seed)) {
				return seed;
			}
		}
		return null;
	}

	/** Replanta a mesma coisa que colheu (a colheita sempre solta semente). */
	private void replant(ServerPlayer me, ServerLevel level) {
		if (lastHarvest == null || replant == null || !level.getBlockState(lastHarvest).isAir()
				|| !level.getBlockState(lastHarvest.below()).is(Blocks.FARMLAND)) {
			return;
		}
		Item seed = has(me, replant) ? replant : bestSeed(me);
		if (seed != null) {
			Placer.place(me, seed, lastHarvest);
		}
		lastHarvest = null;
	}

	private static boolean useHoe(ServerPlayer me, SkillContext ctx, BlockPos pos) {
		var inv = me.getInventory();
		int slot = Stacks.find(inv, s -> s.is(ItemTags.HOES));
		if (slot < 0) {
			return false;
		}
		Stacks.hold(inv, slot);
		Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
		ctx.controls().lookAt(top);
		ItemStack hoe = me.getItemInHand(InteractionHand.MAIN_HAND);
		var result = me.gameMode.useItemOn(me, ctx.level(), hoe, InteractionHand.MAIN_HAND,
				new BlockHitResult(top, Direction.UP, pos, false));
		if (result.consumesAction()) {
			me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		}
		return ctx.level().getBlockState(pos).is(Blocks.FARMLAND);
	}

	/** Pega o que caiu da colheita. true quando acabou. */
	private boolean pickup(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (++pickupTicks < 5) {
			return false;
		}
		BlockPos around = lastHarvest != null ? lastHarvest : me.blockPosition();
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

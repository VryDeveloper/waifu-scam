package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Crafter;
import dev.vry.waifuscam.hands.Food;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Guardar no baú ou pegar do baú mais perto. Guardar sem dizer o quê guarda
 * tudo menos ferramentas, armas, armadura e comida.
 */
public final class ChestSkill implements Skill {
	public enum Mode { DEPOSIT, WITHDRAW }

	private static final int SEARCH_RADIUS = 24;

	private final Mode mode;
	private final Choice items; // null = tudo (só para guardar)
	private final int count;
	private final String label;

	private final List<BlockPos> tried = new ArrayList<>();
	private BlockPos chest;
	private Skill walk;
	private int moved;
	private String failure;

	public ChestSkill(Mode mode, Choice items, int count, String label) {
		this.mode = mode;
		this.items = items;
		this.count = count <= 0 ? Integer.MAX_VALUE : count;
		this.label = label;
	}

	@Override
	public String name() {
		return mode == Mode.DEPOSIT ? "deposit" : "withdraw";
	}

	@Override
	public String describe() {
		return (mode == Mode.DEPOSIT ? "guardando " : "pegando ") + label + (mode == Mode.DEPOSIT ? " no baú" : " do baú");
	}

	private static boolean isStorage(BlockState s) {
		return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (chest == null) {
			List<BlockPos> found = BlockFinder.find(ctx.level(), ctx.player().blockPosition(), SEARCH_RADIUS, 8,
					p -> isStorage(ctx.level().getBlockState(p)) && !tried.contains(p.immutable()));
			if (found.isEmpty()) {
				if (moved > 0) {
					return Status.SUCCEEDED;
				}
				return fail(tried.isEmpty() ? "não achei baú por perto" : mode == Mode.DEPOSIT ? "os baús estão cheios" : "não achei " + label + " nos baús");
			}
			chest = found.get(0);
			walk = null;
		}
		if (!ctx.player().isWithinBlockInteractionRange(chest, 0)) {
			if (walk == null) {
				walk = new GotoSkill(LevelTerrain.of(chest), 3.0, "baú");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				tried.add(chest);
				chest = null;
			}
			return Status.RUNNING;
		}
		ctx.controls().lookAt(Vec3.atCenterOf(chest));
		Container c = container(ctx, chest);
		tried.add(chest);
		if (c == null) {
			chest = null;
			return Status.RUNNING;
		}
		ctx.level().playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
		moved += mode == Mode.DEPOSIT ? deposit(ctx.player().getInventory(), c) : withdraw(ctx, c);
		c.setChanged();
		ctx.level().playSound(null, chest, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
		chest = null;
		boolean done = mode == Mode.WITHDRAW ? moved >= count : remainingToDeposit(ctx.player().getInventory()) == 0;
		return done ? Status.SUCCEEDED : Status.RUNNING; // não coube ou não tinha: tenta o próximo baú
	}

	private static Container container(SkillContext ctx, BlockPos pos) {
		BlockState s = ctx.level().getBlockState(pos);
		if (s.getBlock() instanceof ChestBlock chestBlock) {
			return ChestBlock.getContainer(chestBlock, s, ctx.level(), pos, true);
		}
		BlockEntity be = ctx.level().getBlockEntity(pos);
		return be instanceof Container c ? c : null;
	}

	private boolean keep(ItemStack s) {
		return s.has(DataComponents.TOOL) || s.has(DataComponents.WEAPON) || s.has(DataComponents.EQUIPPABLE)
				|| (Food.edible(s) && !Food.bad(s));
	}

	private boolean wanted(ItemStack s) {
		return items == null ? !keep(s) : items.accepts(Stacks.id(s));
	}

	private int remainingToDeposit(Inventory inv) {
		return Math.max(0, Math.min(count - moved, Stacks.count(inv, this::wanted)));
	}

	private int deposit(Inventory inv, Container c) {
		int n = 0;
		for (int i = 0; i < Stacks.MAIN_SLOTS && moved + n < count; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !wanted(s)) {
				continue;
			}
			int want = Math.min(s.getCount(), count - moved - n);
			ItemStack moving = s.split(want);
			int before = moving.getCount();
			insert(c, moving);
			n += before - moving.getCount();
			if (!moving.isEmpty()) {
				s.grow(moving.getCount()); // não coube: volta
				break;
			}
		}
		inv.setChanged();
		return n;
	}

	private static void insert(Container c, ItemStack moving) {
		for (int j = 0; j < c.getContainerSize() && !moving.isEmpty(); j++) {
			ItemStack there = c.getItem(j);
			if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, moving)) {
				int room = Math.min(moving.getCount(), there.getMaxStackSize() - there.getCount());
				there.grow(room);
				moving.shrink(room);
			}
		}
		for (int j = 0; j < c.getContainerSize() && !moving.isEmpty(); j++) {
			if (c.getItem(j).isEmpty()) {
				c.setItem(j, moving.split(moving.getCount()));
			}
		}
	}

	private int withdraw(SkillContext ctx, Container c) {
		int n = 0;
		for (int j = 0; j < c.getContainerSize() && moved + n < count; j++) {
			ItemStack s = c.getItem(j);
			if (s.isEmpty() || items == null || !items.accepts(Stacks.id(s))) {
				continue;
			}
			ItemStack taken = s.split(Math.min(s.getCount(), count - moved - n));
			n += taken.getCount();
			Crafter.give(ctx.player(), taken);
		}
		return n;
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

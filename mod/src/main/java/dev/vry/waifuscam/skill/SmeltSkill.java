package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.SmeltRecipe;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Crafter;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Fundir na fornalha: põe os itens e o combustível, espera assar de verdade
 * (10 s por item) e pega o resultado. Usa uma fornalha vazia por perto ou coloca
 * a dela.
 */
public final class SmeltSkill implements Skill {
	private static final int SLOT_INPUT = 0;
	private static final int SLOT_FUEL = 1;
	private static final int SLOT_RESULT = 2;

	private final SmeltRecipe recipe;
	private final int times;
	private final GameKnowledge knowledge;

	private BlockPos furnace;
	private Skill walk;
	private boolean loaded;
	private int waited;
	private int startResult;
	private String failure;

	public SmeltSkill(SmeltRecipe recipe, int times, GameKnowledge knowledge) {
		this.recipe = recipe;
		this.times = Math.min(64, times);
		this.knowledge = knowledge;
	}

	@Override
	public String name() {
		return "smelt";
	}

	@Override
	public String describe() {
		return loaded ? "esperando a fornalha (" + Names.pretty(recipe.output()) + ")" : "indo fundir " + Names.pretty(recipe.output());
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (furnace != null && !ctx.level().getBlockState(furnace).is(Blocks.FURNACE)) {
			return fail("a fornalha sumiu");
		}
		if (furnace == null) {
			List<BlockPos> found = BlockFinder.find(ctx.level(), ctx.player().blockPosition(), 16, 4,
					p -> ctx.level().getBlockState(p).is(Blocks.FURNACE) && free(ctx, p));
			if (!found.isEmpty()) {
				furnace = found.get(0);
			} else {
				furnace = Placer.placeNear(ctx.player(), Items.FURNACE);
				if (furnace == null) {
					return fail("preciso de uma fornalha");
				}
			}
		}
		if (!ctx.player().isWithinBlockInteractionRange(furnace, 0)) {
			if (walk == null) {
				walk = new GotoSkill(LevelTerrain.of(furnace), 3.0, "fornalha");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				return fail("não consigo chegar na fornalha");
			}
			return Status.RUNNING;
		}
		walk = null;
		ctx.controls().lookAt(Vec3.atCenterOf(furnace));
		AbstractFurnaceBlockEntity be = entity(ctx);
		if (be == null) {
			return fail("a fornalha quebrou");
		}
		if (!loaded) {
			return load(ctx, be);
		}

		// Esperando: pega quando terminou ou quando acabou o fogo.
		ItemStack result = be.getItem(SLOT_RESULT);
		boolean finished = result.getCount() - startResult >= times;
		boolean stalled = be.getItem(SLOT_INPUT).isEmpty() || ++waited > times * 260 + 400;
		if (finished || stalled) {
			ItemStack out = be.removeItemNoUpdate(SLOT_RESULT);
			be.setChanged();
			if (!out.isEmpty()) {
				Crafter.give(ctx.player(), out);
				be.awardUsedRecipesAndPopExperience(ctx.player());
			}
			if (!finished && be.getItem(SLOT_INPUT).isEmpty() && out.getCount() < times) {
				return out.isEmpty() ? fail("a fornalha não assou nada") : Status.SUCCEEDED;
			}
			if (!finished) {
				// Acabou o combustível: pega de volta o que não assou.
				Crafter.give(ctx.player(), be.removeItemNoUpdate(SLOT_INPUT));
				return fail("acabou o combustível no meio");
			}
			return Status.SUCCEEDED;
		}
		return Status.RUNNING;
	}

	private AbstractFurnaceBlockEntity entity(SkillContext ctx) {
		BlockEntity be = ctx.level().getBlockEntity(furnace);
		return be instanceof AbstractFurnaceBlockEntity f ? f : null;
	}

	private boolean free(SkillContext ctx, BlockPos p) {
		BlockEntity be = ctx.level().getBlockEntity(p);
		if (!(be instanceof AbstractFurnaceBlockEntity f)) {
			return false;
		}
		ItemStack in = f.getItem(SLOT_INPUT);
		ItemStack res = f.getItem(SLOT_RESULT);
		// Livre = vazia, ou só com o mesmo que ela vai assar (não mexe no dos outros).
		return (in.isEmpty() || recipe.input().accepts(Stacks.id(in)))
				&& (res.isEmpty() || Stacks.id(res).equals(recipe.output()));
	}

	private Status load(SkillContext ctx, AbstractFurnaceBlockEntity be) {
		Inventory inv = ctx.player().getInventory();
		ItemStack current = be.getItem(SLOT_INPUT);
		if (!current.isEmpty() && !recipe.input().accepts(Stacks.id(current))) {
			return fail("a fornalha está ocupada");
		}
		int slot = Stacks.find(inv, s -> recipe.input().accepts(Stacks.id(s)));
		if (slot < 0) {
			return fail("não tenho " + Names.pretty(recipe.input().items().get(0)) + " para fundir");
		}
		ItemStack source = inv.getItem(slot);
		int n = Math.min(times, source.getCount());
		if (current.isEmpty()) {
			be.setItem(SLOT_INPUT, source.split(n));
		} else {
			int room = Math.min(n, current.getMaxStackSize() - current.getCount());
			source.shrink(room);
			current.grow(room);
		}

		// Combustível: carvão, depois tronco, depois tábua; nunca ferramenta.
		double needed = times - fuelLeft(be);
		while (needed > 0) {
			int fuel = bestFuel(inv);
			if (fuel < 0) {
				break;
			}
			ItemStack f = inv.getItem(fuel);
			double value = knowledge.fuelValue(Stacks.id(f));
			int use = (int) Math.min(f.getCount(), Math.ceil(needed / value));
			ItemStack inFurnace = be.getItem(SLOT_FUEL);
			if (inFurnace.isEmpty()) {
				be.setItem(SLOT_FUEL, f.split(use));
			} else if (ItemStack.isSameItemSameComponents(inFurnace, f)) {
				int room = Math.min(use, inFurnace.getMaxStackSize() - inFurnace.getCount());
				f.shrink(room);
				inFurnace.grow(room);
				use = room;
			} else {
				break; // tem outro combustível lá; usa o que tem
			}
			needed -= use * value;
		}
		if (be.getItem(SLOT_FUEL).isEmpty() && !lit(be)) {
			Crafter.give(ctx.player(), be.removeItemNoUpdate(SLOT_INPUT));
			return fail("não tenho combustível");
		}
		inv.setChanged();
		be.setChanged();
		startResult = be.getItem(SLOT_RESULT).getCount();
		loaded = true;
		return Status.RUNNING;
	}

	private double fuelLeft(AbstractFurnaceBlockEntity be) {
		ItemStack f = be.getItem(SLOT_FUEL);
		return f.isEmpty() ? 0 : f.getCount() * knowledge.fuelValue(Stacks.id(f));
	}

	private static boolean lit(AbstractFurnaceBlockEntity be) {
		return be.getBlockState().hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)
				&& be.getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
	}

	private int bestFuel(Inventory inv) {
		int best = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.has(DataComponents.TOOL) || s.has(DataComponents.WEAPON) || s.isDamageableItem()) {
				continue;
			}
			String id = Stacks.id(s);
			if (recipe.input().accepts(id)) {
				continue;
			}
			double v = knowledge.fuelValue(id);
			if (v <= 0) {
				continue;
			}
			int rank = id.endsWith("coal") || id.endsWith("charcoal") ? 0
					: id.endsWith("_log") || id.endsWith("_wood") ? 1
					: id.endsWith("_planks") ? 2 : id.equals("minecraft:stick") ? 4 : 3;
			if (rank < bestRank) {
				best = i;
				bestRank = rank;
			}
		}
		return best;
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return loaded;
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

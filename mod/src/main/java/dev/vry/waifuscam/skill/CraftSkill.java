package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.CraftRecipe;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Crafter;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Craftar uma receita algumas vezes. Receitas 3x3 precisam de bancada: usa uma
 * por perto ou coloca a dela no chão.
 */
public final class CraftSkill implements Skill {
	private static final int TABLE_SEARCH_RADIUS = 16;

	private final CraftRecipe recipe;
	private final int times;
	private final GameKnowledge knowledge;

	private BlockPos table;
	private Skill walk;
	private int done;
	private int wait = Human.craft();
	private String failure;

	public CraftSkill(CraftRecipe recipe, int times, GameKnowledge knowledge) {
		this.recipe = recipe;
		this.times = times;
		this.knowledge = knowledge;
	}

	@Override
	public String name() {
		return "craft";
	}

	@Override
	public String describe() {
		return "craftando " + Names.pretty(recipe.output());
	}

	@Override
	public Status tick(SkillContext ctx) {
		RecipeHolder<?> holder = knowledge.holder(recipe.id());
		if (holder == null || !(holder.value() instanceof CraftingRecipe crafting)) {
			return fail("essa receita sumiu do jogo");
		}
		if (recipe.needsTable()) {
			Status s = ensureTable(ctx);
			if (s != Status.SUCCEEDED) {
				return s;
			}
			ctx.controls().lookAt(Vec3.atCenterOf(table));
		}
		if (wait-- > 0) {
			return Status.RUNNING;
		}
		String error = Crafter.craftOnce(ctx.player(), crafting);
		if (error != null) {
			return fail("não deu para craftar " + Names.pretty(recipe.output()) + ": " + error);
		}
		ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		wait = Human.craft();
		return ++done >= times ? Status.SUCCEEDED : Status.RUNNING;
	}

	/** SUCCEEDED quando está do lado de uma bancada. */
	private Status ensureTable(SkillContext ctx) {
		if (table != null && !ctx.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
			table = null;
		}
		if (table == null) {
			List<BlockPos> found = BlockFinder.find(ctx.level(), ctx.player().blockPosition(), TABLE_SEARCH_RADIUS, 1,
					p -> ctx.level().getBlockState(p).is(Blocks.CRAFTING_TABLE));
			if (!found.isEmpty()) {
				table = found.get(0);
			} else {
				table = Placer.placeNear(ctx.player(), Items.CRAFTING_TABLE);
				if (table == null) {
					return fail(ctx.player().getInventory().contains(new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE))
							? "não achei lugar para pôr a bancada" : "preciso de uma bancada");
				}
				return Status.RUNNING;
			}
		}
		if (ctx.player().isWithinBlockInteractionRange(table, 0)) {
			walk = null;
			return Status.SUCCEEDED;
		}
		if (walk == null) {
			walk = new GotoSkill(LevelTerrain.of(table), 3.0, "bancada");
		}
		Status s = walk.tick(ctx);
		if (s == Status.FAILED) {
			// Bancada inalcançável: tenta colocar uma nova, se tiver.
			BlockPos placed = Placer.placeNear(ctx.player(), Items.CRAFTING_TABLE);
			if (placed == null) {
				return fail("não consigo chegar na bancada");
			}
			table = placed;
			walk = null;
		}
		return Status.RUNNING;
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

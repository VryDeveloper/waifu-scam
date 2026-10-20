package dev.vry.waifuscam.hands;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Crafta uma vez a partir do inventário dela. Monta a grade com os itens que ela
 * tem, confere com a receita do jogo (matches) e só então troca os ingredientes
 * pelo resultado — nada aparece do nada.
 */
public final class Crafter {
	private Crafter() {}

	/** Devolve null se deu certo, ou o motivo. */
	public static String craftOnce(ServerPlayer player, CraftingRecipe recipe) {
		Inventory inv = player.getInventory();
		int width;
		int height;
		List<Optional<Ingredient>> slots;
		if (recipe instanceof ShapedRecipe shaped) {
			width = shaped.getWidth();
			height = shaped.getHeight();
			slots = shaped.getIngredients();
		} else if (recipe instanceof ShapelessRecipe) {
			List<Ingredient> ings = recipe.placementInfo().ingredients();
			width = 3;
			height = 3;
			slots = new ArrayList<>();
			for (Ingredient i : ings) {
				slots.add(Optional.of(i));
			}
			while (slots.size() < 9) {
				slots.add(Optional.empty());
			}
		} else {
			return "não sei fazer essa receita";
		}

		// Escolhe de qual slot do inventário sai cada ingrediente.
		int[] used = new int[inv.getContainerSize()];
		int[] from = new int[slots.size()];
		List<ItemStack> grid = new ArrayList<>();
		for (int i = 0; i < slots.size(); i++) {
			Optional<Ingredient> slot = slots.get(i);
			from[i] = -1;
			if (slot.isEmpty()) {
				grid.add(ItemStack.EMPTY);
				continue;
			}
			int found = -1;
			for (int s = 0; s < Stacks.MAIN_SLOTS; s++) {
				ItemStack stack = inv.getItem(s);
				if (!stack.isEmpty() && stack.getCount() > used[s] && slot.get().test(stack)) {
					found = s;
					break;
				}
			}
			if (found < 0) {
				return "faltou ingrediente";
			}
			used[found]++;
			from[i] = found;
			grid.add(inv.getItem(found).copyWithCount(1));
		}

		CraftingInput input = CraftingInput.of(width, height, grid);
		if (!recipe.matches(input, player.level())) {
			return "a receita não bateu";
		}
		ItemStack result = recipe.assemble(input);
		if (result.isEmpty()) {
			return "a receita não deu nada";
		}
		NonNullList<ItemStack> remaining = recipe.getRemainingItems(input);

		for (int s = 0; s < used.length; s++) {
			if (used[s] > 0) {
				inv.getItem(s).shrink(used[s]);
				if (inv.getItem(s).isEmpty()) {
					inv.setItem(s, ItemStack.EMPTY);
				}
			}
		}
		result.onCraftedBy(player, result.getCount());
		give(player, result);
		for (ItemStack r : remaining) {
			if (!r.isEmpty()) {
				give(player, r); // balde vazio de volta, etc.
			}
		}
		inv.setChanged();
		return null;
	}

	/** Põe no inventário; o que não couber cai no chão aos pés dela. */
	public static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack) && !stack.isEmpty()) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}
}

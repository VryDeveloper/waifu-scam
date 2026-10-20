package dev.vry.waifuscam.hands;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** O que ela come e quando. */
public final class Food {
	private Food() {}

	/** Comida que faz mal (fome, veneno, teleporte): só em último caso. */
	public static boolean bad(ItemStack s) {
		return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
				|| s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW);
	}

	public static boolean edible(ItemStack s) {
		return !s.isEmpty() && s.has(DataComponents.FOOD) && s.has(DataComponents.CONSUMABLE);
	}

	/**
	 * Melhor comida do inventário para agora: a que mais enche sem desperdiçar.
	 * Comida ruim só se ela estiver passando fome de verdade. -1 = nada.
	 */
	public static int best(ServerPlayer player) {
		Inventory inv = player.getInventory();
		int hunger = 20 - player.getFoodData().getFoodLevel();
		boolean starving = player.getFoodData().getFoodLevel() <= 6;
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (!edible(s) || (bad(s) && !starving) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) {
				continue;
			}
			FoodProperties food = s.get(DataComponents.FOOD);
			int n = food.nutrition();
			// Encher o máximo sem passar muito do que falta; comida ruim perde pontos.
			double score = Math.min(n, hunger) - Math.max(0, n - hunger) * 0.5 + food.saturation() * 0.1;
			if (bad(s)) {
				score -= 10;
			}
			if (score > bestScore) {
				best = i;
				bestScore = score;
			}
		}
		return best;
	}

	public static boolean hasFood(ServerPlayer player) {
		return Stacks.find(player.getInventory(), s -> edible(s) && !bad(s)) >= 0;
	}
}

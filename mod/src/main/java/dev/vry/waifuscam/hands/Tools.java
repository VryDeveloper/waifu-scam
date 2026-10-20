package dev.vry.waifuscam.hands;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Escolher a ferramenta certa, como um player faria antes de minerar. */
public final class Tools {
	private Tools() {}

	/**
	 * Põe na mão o que quebra o bloco mais rápido, sem desperdiçar ferramenta boa
	 * em bloco que sai na mão (terra com picareta de diamante, por exemplo).
	 * Devolve false se o bloco precisa de ferramenta e ela não tem nenhuma que sirva.
	 */
	public static boolean equipFor(ServerPlayer player, BlockState state) {
		Inventory inv = player.getInventory();
		int best = -1;
		float bestScore = 1.0F; // a mão
		boolean needsTool = state.requiresCorrectToolForDrops();
		for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !s.has(DataComponents.TOOL) || almostBroken(s)) {
				continue;
			}
			if (needsTool && !s.isCorrectToolForDrops(state)) {
				continue;
			}
			float speed = s.getDestroySpeed(state);
			// Entre ferramentas igualmente boas, a mais gasta primeiro.
			float score = speed - (s.isDamageableItem() ? 0.001F * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage() : 0);
			if (speed > 1.0F && score > bestScore) {
				best = i;
				bestScore = score;
			}
		}
		if (best >= 0) {
			Stacks.hold(inv, best);
			return true;
		}
		if (needsTool) {
			return false;
		}
		// Sem ferramenta útil: tira da mão o que quebraria à toa (espada perde durabilidade).
		ItemStack hand = inv.getSelectedItem();
		if (!hand.isEmpty() && hand.isDamageableItem()) {
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				if (inv.getItem(i).isEmpty()) {
					inv.setSelectedSlot(i);
					break;
				}
			}
		}
		return true;
	}

	/** Ela não usa uma ferramenta até quebrar (deixa 2 de durabilidade). */
	public static boolean almostBroken(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2;
	}

	/** Tem como colher este bloco com o que está no inventário? */
	public static boolean canHarvest(ServerPlayer player, BlockState state) {
		if (!state.requiresCorrectToolForDrops()) {
			return true;
		}
		return Stacks.find(player.getInventory(), s -> s.isCorrectToolForDrops(state) && !almostBroken(s)) >= 0;
	}
}

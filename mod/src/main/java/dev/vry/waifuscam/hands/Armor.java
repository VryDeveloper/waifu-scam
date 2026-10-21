package dev.vry.waifuscam.hands;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Vestir a melhor armadura que tiver, e escudo na outra mão. */
public final class Armor {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** Peças que pediram para ela tirar: não veste de novo sozinha até pedirem (equip). */
	private static final Map<UUID, Set<EquipmentSlot>> KEPT_OFF = new ConcurrentHashMap<>();

	private Armor() {}

	public static boolean keptOff(ServerPlayer me, EquipmentSlot slot) {
		Set<EquipmentSlot> off = KEPT_OFF.get(me.getUUID());
		return off != null && off.contains(slot);
	}

	public static void keepOff(ServerPlayer me, Set<EquipmentSlot> slots) {
		KEPT_OFF.computeIfAbsent(me.getUUID(), k -> EnumSet.noneOf(EquipmentSlot.class)).addAll(slots);
	}

	public static void allow(ServerPlayer me, Set<EquipmentSlot> slots) {
		Set<EquipmentSlot> off = KEPT_OFF.get(me.getUUID());
		if (off != null) {
			off.removeAll(slots);
		}
	}

	public static boolean keepingAnyOff(ServerPlayer me) {
		Set<EquipmentSlot> off = KEPT_OFF.get(me.getUUID());
		return off != null && !off.isEmpty();
	}

	public static EquipmentSlot[] armorSlots() {
		return SLOTS.clone();
	}

	/** Pontos de armadura (mais um pouco de dureza) que a peça dá naquele slot. */
	static double score(ItemStack stack, EquipmentSlot slot) {
		if (stack.isEmpty()) {
			return 0;
		}
		double[] total = {0};
		stack.forEachModifier(slot, (attribute, modifier) -> {
			if (attribute.is(Attributes.ARMOR)) {
				total[0] += modifier.amount();
			} else if (attribute.is(Attributes.ARMOR_TOUGHNESS)) {
				total[0] += modifier.amount() * 0.5;
			}
		});
		// Peça quase quebrada vale menos que uma inteira.
		if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
			total[0] *= 0.6 + 0.4 * (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage();
		}
		return total[0];
	}

	/** Troca cada peça por uma melhor do inventário. Devolve true se vestiu algo. */
	public static boolean equipBest(ServerPlayer me) {
		Inventory inv = me.getInventory();
		boolean changed = false;
		for (EquipmentSlot slot : SLOTS) {
			if (keptOff(me, slot)) {
				continue;
			}
			ItemStack worn = me.getItemBySlot(slot);
			double best = score(worn, slot);
			int bestSlot = -1;
			for (int i = 0; i < Stacks.MAIN_SLOTS; i++) {
				ItemStack s = inv.getItem(i);
				Equippable eq = s.isEmpty() ? null : s.get(DataComponents.EQUIPPABLE);
				if (eq == null || eq.slot() != slot) {
					continue;
				}
				double sc = score(s, slot);
				if (sc > best + 0.01) {
					best = sc;
					bestSlot = i;
				}
			}
			if (bestSlot >= 0) {
				ItemStack better = inv.getItem(bestSlot);
				inv.setItem(bestSlot, worn.copy());
				me.setItemSlot(slot, better.copy());
				changed = true;
			}
		}
		// Escudo na mão de fora, se ela tiver um e a mão estiver livre.
		if (me.getOffhandItem().isEmpty() && !keptOff(me, EquipmentSlot.OFFHAND)) {
			int shield = Stacks.find(inv, s -> s.is(Items.SHIELD));
			if (shield >= 0) {
				me.setItemSlot(EquipmentSlot.OFFHAND, inv.getItem(shield).copy());
				inv.setItem(shield, ItemStack.EMPTY);
				changed = true;
			}
		}
		if (changed) {
			inv.setChanged();
		}
		return changed;
	}
}

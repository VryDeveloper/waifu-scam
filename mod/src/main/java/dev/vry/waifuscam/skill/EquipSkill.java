package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.hands.Armor;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.equipment.Equippable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tirar ou vestir armadura e escudo.
 * - unequip: tira as peças pedidas (armadura toda, uma peça, o escudo) e guarda
 *   no inventário; ela não veste de novo sozinha até pedirem.
 * - equip: veste de novo (a melhor que tiver), ou uma peça específica do inventário.
 */
public final class EquipSkill implements Skill {
	private final boolean on;
	private final String what; // "armor", "all", "helmet", "shield", um id de item... ou null
	private int wait = Human.reaction();
	private String failure;

	public EquipSkill(boolean on, String what) {
		this.on = on;
		this.what = what == null || what.isBlank() ? null : what.toLowerCase(Locale.ROOT).replace("minecraft:", "");
	}

	@Override
	public String name() {
		return on ? "equip" : "unequip";
	}

	@Override
	public String describe() {
		return on ? "vestindo a armadura" : "tirando a armadura";
	}

	/** Os slots que a palavra quer dizer; null se for o id de uma peça. */
	static Set<EquipmentSlot> slots(String what, boolean on) {
		if (what == null) {
			return on ? EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)
					: EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
		}
		return switch (what) {
			case "armor", "armadura", "#armor", "armadura_toda", "armadura_completa" ->
					EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
			case "all", "tudo", "everything", "equipment", "equipamentos" ->
					EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);
			case "head", "helmet", "capacete", "cabeça", "cabeca" -> EnumSet.of(EquipmentSlot.HEAD);
			case "chest", "chestplate", "peitoral", "peito" -> EnumSet.of(EquipmentSlot.CHEST);
			case "legs", "leggings", "calça", "calca", "calças", "calcas" -> EnumSet.of(EquipmentSlot.LEGS);
			case "feet", "boots", "botas", "bota", "pés", "pes" -> EnumSet.of(EquipmentSlot.FEET);
			case "shield", "escudo", "offhand" -> EnumSet.of(EquipmentSlot.OFFHAND);
			default -> null;
		};
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (wait-- > 0) {
			return Status.RUNNING;
		}
		Set<EquipmentSlot> slots = slots(what, on);
		if (slots == null) {
			slots = slotOfItem(me);
			if (slots == null) {
				return fail(on ? "não tenho " + what + " para vestir" : "não estou vestindo " + what);
			}
		}
		me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		return on ? putOn(me, slots) : takeOff(me, slots);
	}

	/** "iron_helmet" → o slot dele (vestido, para tirar; ou no inventário, para vestir). */
	private Set<EquipmentSlot> slotOfItem(ServerPlayer me) {
		for (EquipmentSlot slot : Stacks.WORN) {
			ItemStack s = me.getItemBySlot(slot);
			if (!s.isEmpty() && Stacks.id(s).replace("minecraft:", "").equals(what)) {
				return EnumSet.of(slot);
			}
		}
		if (on) {
			int i = Stacks.find(me.getInventory(), s -> Stacks.id(s).replace("minecraft:", "").equals(what));
			if (i >= 0) {
				return EnumSet.of(slotFor(me.getInventory().getItem(i)));
			}
		}
		return null;
	}

	private static EquipmentSlot slotFor(ItemStack s) {
		if (s.is(Items.SHIELD)) {
			return EquipmentSlot.OFFHAND;
		}
		Equippable eq = s.get(DataComponents.EQUIPPABLE);
		return eq != null ? eq.slot() : EquipmentSlot.OFFHAND;
	}

	private Status takeOff(ServerPlayer me, Set<EquipmentSlot> slots) {
		Inventory inv = me.getInventory();
		int taken = 0;
		for (EquipmentSlot slot : slots) {
			ItemStack worn = me.getItemBySlot(slot);
			if (worn.isEmpty()) {
				continue;
			}
			ItemStack copy = worn.copy();
			me.setItemSlot(slot, ItemStack.EMPTY);
			if (!inv.add(copy) && !copy.isEmpty()) {
				me.drop(copy, false, net.minecraft.util.Prediction.SERVER_ONLY); // mochila cheia: fica no chão
			}
			taken++;
		}
		Armor.keepOff(me, slots);
		inv.setChanged();
		return taken > 0 ? Status.SUCCEEDED : fail("não estou vestindo nada aí");
	}

	private Status putOn(ServerPlayer me, Set<EquipmentSlot> slots) {
		Armor.allow(me, slots);
		if (what != null && slots(what, true) == null) {
			// Uma peça específica: veste essa, mesmo que não seja a melhor.
			Inventory inv = me.getInventory();
			int i = Stacks.find(inv, s -> Stacks.id(s).replace("minecraft:", "").equals(what));
			if (i < 0) {
				return Status.SUCCEEDED; // já estava vestida
			}
			EquipmentSlot slot = slotFor(inv.getItem(i));
			ItemStack piece = inv.getItem(i).copy();
			inv.setItem(i, me.getItemBySlot(slot).copy());
			me.setItemSlot(slot, piece);
			inv.setChanged();
			return Status.SUCCEEDED;
		}
		Armor.equipBest(me);
		return Status.SUCCEEDED;
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

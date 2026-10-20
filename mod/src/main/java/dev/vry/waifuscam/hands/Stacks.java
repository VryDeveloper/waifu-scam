package dev.vry.waifuscam.hands;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Ids de item, contagem do inventário e troca de slots. */
public final class Stacks {
	/** Slots do inventário principal (0-8 hotbar, 9-35 mochila). */
	public static final int MAIN_SLOTS = 36;

	/** Apelidos que a IA (ou alguém no chat) costuma usar. */
	private static final Map<String, String> ALIASES = Map.ofEntries(
			Map.entry("madeira", "#minecraft:logs"),
			Map.entry("tronco", "#minecraft:logs"),
			Map.entry("troncos", "#minecraft:logs"),
			Map.entry("log", "#minecraft:logs"),
			Map.entry("logs", "#minecraft:logs"),
			Map.entry("wood", "#minecraft:logs"),
			Map.entry("tabua", "#minecraft:planks"),
			Map.entry("tábua", "#minecraft:planks"),
			Map.entry("tabuas", "#minecraft:planks"),
			Map.entry("tábuas", "#minecraft:planks"),
			Map.entry("planks", "#minecraft:planks"),
			Map.entry("pedra", "minecraft:cobblestone"),
			Map.entry("pedregulho", "minecraft:cobblestone"),
			Map.entry("graveto", "minecraft:stick"),
			Map.entry("gravetos", "minecraft:stick"),
			Map.entry("carvao", "#minecraft:coals"),
			Map.entry("carvão", "#minecraft:coals"),
			Map.entry("coal", "#minecraft:coals"),
			Map.entry("ferro", "minecraft:iron_ingot"),
			Map.entry("iron", "minecraft:iron_ingot"),
			Map.entry("terra", "minecraft:dirt"),
			Map.entry("bancada", "minecraft:crafting_table"),
			Map.entry("fornalha", "minecraft:furnace"),
			Map.entry("tocha", "minecraft:torch"),
			Map.entry("tochas", "minecraft:torch"),
			Map.entry("bau", "minecraft:chest"),
			Map.entry("baú", "minecraft:chest"));

	private Stacks() {}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	public static String id(ItemStack stack) {
		return id(stack.getItem());
	}

	public static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	/** Item pelo id ("stone_pickaxe" ou "minecraft:stone_pickaxe"); null se não existir. */
	public static Item item(String id) {
		Identifier key = Identifier.tryParse(normalize(id));
		if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getValue(key);
		return item == Items.AIR ? null : item;
	}

	public static String normalize(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		String alias = ALIASES.get(s);
		if (alias != null) {
			return alias;
		}
		boolean tag = s.startsWith("#");
		if (tag) {
			s = s.substring(1);
		}
		if (!s.contains(":")) {
			s = "minecraft:" + s;
		}
		return tag ? "#" + s : s;
	}

	/**
	 * Itens aceitos por um pedido: um item ou uma tag ("#minecraft:logs"). Plural
	 * simples também funciona ("torches"). Vazio = não existe.
	 */
	public static List<String> resolve(String raw) {
		String id = normalize(raw);
		List<String> out = new ArrayList<>();
		if (id.startsWith("#")) {
			Identifier key = Identifier.tryParse(id.substring(1));
			if (key != null) {
				TagKey<Item> tag = TagKey.create(Registries.ITEM, key);
				for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
					out.add(id(h.value()));
				}
			}
			return out;
		}
		if (item(id) != null) {
			out.add(id);
		} else if (id.endsWith("es") && item(id.substring(0, id.length() - 2)) != null) {
			out.add(id.substring(0, id.length() - 2)); // torches → torch
		} else if (id.endsWith("s") && item(id.substring(0, id.length() - 1)) != null) {
			out.add(id.substring(0, id.length() - 1)); // sticks → stick
		} else {
			// "logs" sem # também vale como tag
			Identifier key = Identifier.tryParse(id);
			if (key != null) {
				for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, key))) {
					out.add(id(h.value()));
				}
			}
		}
		return out;
	}

	/** Contagem por id dos 36 slots principais + mão secundária. */
	public static Map<String, Integer> counts(Inventory inv) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) {
				out.merge(id(s), s.getCount(), Integer::sum);
			}
		}
		return out;
	}

	public static int count(Inventory inv, Predicate<ItemStack> filter) {
		int n = 0;
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && filter.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public static int count(Inventory inv, List<String> ids) {
		return count(inv, s -> ids.contains(id(s)));
	}

	public static int find(Inventory inv, Predicate<ItemStack> filter) {
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && filter.test(s)) {
				return i;
			}
		}
		return -1;
	}

	/** Tira n itens que passam no filtro. Devolve quantos tirou. */
	public static int remove(Inventory inv, Predicate<ItemStack> filter, int n) {
		int removed = 0;
		for (int i = 0; i < MAIN_SLOTS && removed < n; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && filter.test(s)) {
				int take = Math.min(n - removed, s.getCount());
				s.shrink(take);
				removed += take;
				if (s.isEmpty()) {
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		}
		if (removed > 0) {
			inv.setChanged();
		}
		return removed;
	}

	/**
	 * Põe o item do slot na mão (hotbar selecionada). Se ele estiver na mochila,
	 * troca de lugar com o item do slot selecionado, como o "pick block".
	 */
	public static void hold(Inventory inv, int slot) {
		if (slot < 0) {
			return;
		}
		if (Inventory.isHotbarSlot(slot)) {
			inv.setSelectedSlot(slot);
			return;
		}
		int hotbar = inv.getSelectedSlot();
		ItemStack inHand = inv.getItem(hotbar);
		inv.setItem(hotbar, inv.getItem(slot));
		inv.setItem(slot, inHand);
		inv.setChanged();
	}

	/** Um texto curto do inventário para a Mente: "12 oak_log, 1 stone_pickaxe, ...". */
	public static Map<String, Integer> summary(Inventory inv, int limit) {
		Map<String, Integer> all = counts(inv);
		Map<String, Integer> out = new LinkedHashMap<>();
		all.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(limit)
				.forEach(e -> out.put(e.getKey().replace("minecraft:", ""), e.getValue()));
		return out;
	}
}

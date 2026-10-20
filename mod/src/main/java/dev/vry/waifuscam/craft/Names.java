package dev.vry.waifuscam.craft;

import java.util.Map;

/**
 * Nomes em português dos itens mais comuns, para os motivos de falha que vão para
 * o chat. O servidor não tem as traduções do jogo; o resto vira "stone pickaxe".
 */
public final class Names {
	private static final Map<String, String> PT = Map.ofEntries(
			Map.entry("crafting_table", "bancada"),
			Map.entry("furnace", "fornalha"),
			Map.entry("stick", "graveto"),
			Map.entry("oak_log", "tronco"),
			Map.entry("oak_planks", "tábua"),
			Map.entry("cobblestone", "pedregulho"),
			Map.entry("stone", "pedra"),
			Map.entry("coal", "carvão"),
			Map.entry("charcoal", "carvão vegetal"),
			Map.entry("raw_iron", "ferro bruto"),
			Map.entry("iron_ingot", "barra de ferro"),
			Map.entry("raw_gold", "ouro bruto"),
			Map.entry("gold_ingot", "barra de ouro"),
			Map.entry("raw_copper", "cobre bruto"),
			Map.entry("copper_ingot", "barra de cobre"),
			Map.entry("diamond", "diamante"),
			Map.entry("wooden_pickaxe", "picareta de madeira"),
			Map.entry("stone_pickaxe", "picareta de pedra"),
			Map.entry("copper_pickaxe", "picareta de cobre"),
			Map.entry("iron_pickaxe", "picareta de ferro"),
			Map.entry("diamond_pickaxe", "picareta de diamante"),
			Map.entry("wooden_axe", "machado de madeira"),
			Map.entry("stone_axe", "machado de pedra"),
			Map.entry("iron_axe", "machado de ferro"),
			Map.entry("wooden_shovel", "pá de madeira"),
			Map.entry("stone_shovel", "pá de pedra"),
			Map.entry("iron_shovel", "pá de ferro"),
			Map.entry("wooden_sword", "espada de madeira"),
			Map.entry("stone_sword", "espada de pedra"),
			Map.entry("iron_sword", "espada de ferro"),
			Map.entry("diamond_sword", "espada de diamante"),
			Map.entry("torch", "tocha"),
			Map.entry("chest", "baú"),
			Map.entry("dirt", "terra"),
			Map.entry("sand", "areia"),
			Map.entry("glass", "vidro"),
			Map.entry("bread", "pão"),
			Map.entry("wheat", "trigo"),
			Map.entry("apple", "maçã"),
			Map.entry("bed", "cama"),
			Map.entry("white_bed", "cama"),
			Map.entry("white_wool", "lã"),
			Map.entry("string", "linha"),
			Map.entry("shield", "escudo"),
			Map.entry("bucket", "balde"));

	private Names() {}

	public static String pretty(String id) {
		String path = id.startsWith("#") ? id.substring(1) : id;
		int colon = path.indexOf(':');
		path = colon >= 0 ? path.substring(colon + 1) : path;
		String pt = PT.get(path);
		if (pt != null) {
			return pt;
		}
		if (path.endsWith("_log") || path.equals("logs")) {
			return "tronco";
		}
		if (path.endsWith("_planks") || path.equals("planks")) {
			return "tábua";
		}
		return path.replace('_', ' ');
	}
}

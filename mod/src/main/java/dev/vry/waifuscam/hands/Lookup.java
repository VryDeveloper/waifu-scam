package dev.vry.waifuscam.hands;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Acha bichos e blocos pelo nome que a pessoa (ou a IA) usou: "golem", "zumbi",
 * "iron_golem", "porta", "alavanca". Primeiro apelidos em português, depois o id
 * exato, depois qualquer id que contenha a palavra.
 */
public final class Lookup {
	private static final Map<String, String> ENTITY_PT = Map.ofEntries(
			Map.entry("golem", "iron_golem"), Map.entry("golem_de_ferro", "iron_golem"), Map.entry("zumbi", "zombie"),
			Map.entry("esqueleto", "skeleton"), Map.entry("aranha", "spider"), Map.entry("aranha_de_caverna", "cave_spider"),
			Map.entry("bruxa", "witch"), Map.entry("afogado", "drowned"), Map.entry("fantasma", "phantom"),
			Map.entry("vaca", "cow"), Map.entry("porco", "pig"), Map.entry("ovelha", "sheep"), Map.entry("galinha", "chicken"),
			Map.entry("frango", "chicken"), Map.entry("coelho", "rabbit"), Map.entry("lobo", "wolf"), Map.entry("raposa", "fox"),
			Map.entry("cavalo", "horse"), Map.entry("aldeao", "villager"), Map.entry("aldeão", "villager"),
			Map.entry("slime", "slime"), Map.entry("gosma", "slime"), Map.entry("enderman", "enderman"),
			Map.entry("saqueador", "pillager"), Map.entry("lhama", "llama"), Map.entry("cabra", "goat"));

	private static final Map<String, String> BLOCK_PT = Map.ofEntries(
			Map.entry("porta", "door"), Map.entry("alavanca", "lever"), Map.entry("botao", "button"), Map.entry("botão", "button"),
			Map.entry("bau", "chest"), Map.entry("baú", "chest"), Map.entry("fornalha", "furnace"), Map.entry("bancada", "crafting_table"),
			Map.entry("cama", "bed"), Map.entry("sino", "bell"), Map.entry("portao", "fence_gate"), Map.entry("portão", "fence_gate"),
			Map.entry("alcapao", "trapdoor"), Map.entry("alçapão", "trapdoor"), Map.entry("barril", "barrel"),
			Map.entry("tocha", "torch"), Map.entry("vidro", "glass"), Map.entry("terra", "dirt"), Map.entry("pedra", "stone"),
			Map.entry("madeira", "log"), Map.entry("tronco", "log"), Map.entry("folha", "leaves"), Map.entry("areia", "sand"),
			Map.entry("bigorna", "anvil"), Map.entry("mesa_de_encantamento", "enchanting_table"));

	private Lookup() {}

	static String clean(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
		if (s.endsWith("s") && s.length() > 4 && !s.endsWith("ss")) {
			String singular = s.substring(0, s.length() - 1);
			if (ENTITY_PT.containsKey(singular) || BLOCK_PT.containsKey(singular)) {
				s = singular; // zumbis → zumbi
			}
		}
		return s;
	}

	private static String ascii(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
	}

	/** Tipos de bicho ("minecraft:iron_golem") para o nome. Vazio = não achou. */
	public static Set<String> entities(String raw) {
		String key = clean(raw);
		String alias = ENTITY_PT.getOrDefault(key, ENTITY_PT.getOrDefault(ascii(key), key));
		Set<String> out = new LinkedHashSet<>();
		Identifier exact = Identifier.tryParse("minecraft:" + alias);
		if (exact != null && BuiltInRegistries.ENTITY_TYPE.containsKey(exact)) {
			out.add(exact.toString());
			return out;
		}
		List<EntityType<?>> matches = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().contains(alias)) {
				matches.add(type);
			}
		}
		matches.sort(Comparator.comparingInt(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().length()));
		matches.stream().limit(6).forEach(t -> out.add(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
		return out;
	}

	/** Blocos para o nome ("porta" → todas as portas). Vazio = não achou. */
	public static Set<Block> blocks(String raw) {
		String key = clean(raw);
		String alias = BLOCK_PT.getOrDefault(key, BLOCK_PT.getOrDefault(ascii(key), key));
		Set<Block> out = new LinkedHashSet<>();
		Identifier exact = Identifier.tryParse("minecraft:" + alias);
		if (exact != null && BuiltInRegistries.BLOCK.containsKey(exact)) {
			out.add(BuiltInRegistries.BLOCK.getValue(exact));
			return out;
		}
		for (Block b : BuiltInRegistries.BLOCK) {
			String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (path.endsWith("_" + alias) || path.startsWith(alias + "_") || path.equals(alias)) {
				out.add(b);
			}
		}
		return out;
	}
}

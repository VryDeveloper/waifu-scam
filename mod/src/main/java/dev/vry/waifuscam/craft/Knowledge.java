package dev.vry.waifuscam.craft;

import java.util.List;
import java.util.Set;

/**
 * O que a personagem sabe sobre como conseguir as coisas: receitas, de que bloco
 * sai cada item e qual ferramenta precisa. Os itens são ids ("minecraft:stick").
 *
 * A implementação de verdade (GameKnowledge) lê as receitas e as tags do jogo; os
 * testes usam uma versão em memória. Assim o planejador roda sem o Minecraft.
 */
public interface Knowledge {

	/** Uma posição de ingrediente: aceita qualquer um destes itens. */
	record Choice(List<String> items) {
		public Choice {
			items = List.copyOf(items);
		}

		public static Choice of(String... items) {
			return new Choice(List.of(items));
		}

		public boolean accepts(String item) {
			return items.contains(item);
		}
	}

	/** Receita de bancada (ou do inventário 2x2, quando needsTable é false). */
	record CraftRecipe(String id, String output, int count, List<Choice> inputs, boolean needsTable) {
		public CraftRecipe {
			inputs = List.copyOf(inputs);
		}
	}

	/** Receita de fornalha: 1 item de entrada vira count itens de saída. */
	record SmeltRecipe(String id, String output, int count, Choice input) {}

	/**
	 * De onde um item sai minerando. items = a família aceita (quebrar qualquer tronco
	 * serve quando se pede um tronco), blocks = blocos que soltam esses itens,
	 * tools = ferramentas que servem, da mais barata para a mais cara (vazio = na mão).
	 */
	record Source(Set<String> items, Set<String> blocks, List<String> tools) {
		public Source {
			items = Set.copyOf(items);
			blocks = Set.copyOf(blocks);
			tools = List.copyOf(tools);
		}
	}

	List<CraftRecipe> craftsFor(String item);

	List<SmeltRecipe> smeltsFor(String item);

	/** Fontes naturais do item. Vazio = não dá para minerar. */
	List<Source> sourcesFor(String item);

	/** Quantos itens um deste combustível assa (carvão = 8, tábua = 1,5). 0 = não é combustível. */
	double fuelValue(String item);

	/** Todos os tipos de tábua (combustível padrão quando falta carvão). */
	List<String> planks();

	String craftingTable();

	String furnace();
}

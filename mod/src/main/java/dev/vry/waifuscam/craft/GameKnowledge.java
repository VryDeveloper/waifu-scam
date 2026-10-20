package dev.vry.waifuscam.craft;

import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * O conhecimento de verdade, tirado do jogo: as receitas carregadas no servidor
 * (inclusive de datapacks) e as tags de ferramenta de cada bloco. As fontes
 * naturais são uma lista curada, para ela não desmontar construções: pedregulho
 * sai de pedra, nunca de parede de pedregulho; troncos só de árvore.
 */
public final class GameKnowledge implements Knowledge {

	private static GameKnowledge cached;
	private static MinecraftServer cachedFor;
	private static int cachedRecipeCount;

	/** Mantém o índice enquanto as receitas não mudarem (/reload refaz). */
	public static synchronized GameKnowledge of(MinecraftServer server) {
		int count = server.getRecipeManager().getRecipes().size();
		if (cached == null || cachedFor != server || cachedRecipeCount != count) {
			cached = new GameKnowledge(server);
			cachedFor = server;
			cachedRecipeCount = count;
		}
		return cached;
	}

	private final Map<String, List<CraftRecipe>> crafts = new HashMap<>();
	private final Map<String, List<SmeltRecipe>> smelts = new HashMap<>();
	private final Map<String, RecipeHolder<?>> holders = new HashMap<>();
	private final Map<String, List<Source>> sources = new HashMap<>();
	private final List<String> planks = new ArrayList<>();

	private GameKnowledge(MinecraftServer server) {
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			try {
				index(holder);
			} catch (RuntimeException e) {
				// receita estranha de datapack: ignora
			}
		}
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS)) {
			planks.add(Stacks.id(h.value()));
		}
		planks.remove("minecraft:oak_planks");
		planks.add(0, "minecraft:oak_planks");
		buildSources();
	}

	// ---- receitas ----------------------------------------------------------------

	private void index(RecipeHolder<?> holder) {
		Recipe<?> recipe = holder.value();
		String id = holder.id().identifier().toString();
		if (recipe instanceof ShapedRecipe shaped) {
			List<ItemStack> grid = new ArrayList<>();
			List<Choice> inputs = new ArrayList<>();
			for (Optional<Ingredient> slot : shaped.getIngredients()) {
				if (slot.isEmpty()) {
					grid.add(ItemStack.EMPTY);
					continue;
				}
				Choice choice = choice(slot.get());
				if (choice == null) {
					return;
				}
				inputs.add(choice);
				grid.add(first(slot.get()));
			}
			ItemStack out = shaped.assemble(CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid));
			boolean table = shaped.getWidth() > 2 || shaped.getHeight() > 2;
			addCraft(holder, new CraftRecipe(id, Stacks.id(out), out.getCount(), inputs, table), out);
		} else if (recipe instanceof ShapelessRecipe shapeless) {
			List<Ingredient> ingredients = shapeless.placementInfo().ingredients();
			if (ingredients.isEmpty() || ingredients.size() > 9) {
				return;
			}
			List<ItemStack> grid = new ArrayList<>();
			List<Choice> inputs = new ArrayList<>();
			for (Ingredient ing : ingredients) {
				Choice choice = choice(ing);
				if (choice == null) {
					return;
				}
				inputs.add(choice);
				grid.add(first(ing));
			}
			while (grid.size() < 9) {
				grid.add(ItemStack.EMPTY);
			}
			ItemStack out = shapeless.assemble(CraftingInput.of(3, 3, grid));
			addCraft(holder, new CraftRecipe(id, Stacks.id(out), out.getCount(), inputs, ingredients.size() > 4), out);
		} else if (recipe instanceof SmeltingRecipe smelting) {
			Choice input = choice(smelting.input());
			if (input == null) {
				return;
			}
			ItemStack out = smelting.assemble(new SingleRecipeInput(first(smelting.input())));
			if (out.isEmpty()) {
				return;
			}
			holders.put(id, holder);
			smelts.computeIfAbsent(Stacks.id(out), k -> new ArrayList<>())
					.add(new SmeltRecipe(id, Stacks.id(out), out.getCount(), input));
		}
	}

	private void addCraft(RecipeHolder<?> holder, CraftRecipe recipe, ItemStack out) {
		if (out.isEmpty() || recipe.inputs().isEmpty()) {
			return;
		}
		// Receita que usa o próprio resultado (tingir de novo, etc.) não ajuda a conseguir nada.
		for (Choice in : recipe.inputs()) {
			if (in.accepts(recipe.output())) {
				return;
			}
		}
		holders.put(recipe.id(), holder);
		crafts.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
	}

	private static Choice choice(Ingredient ing) {
		List<String> items = new ArrayList<>();
		ing.items().forEach(h -> items.add(Stacks.id(h.value())));
		return items.isEmpty() ? null : new Choice(items);
	}

	private static ItemStack first(Ingredient ing) {
		return ing.items().findFirst().map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);
	}

	/** A receita do jogo por trás de uma receita do planejador. */
	public RecipeHolder<?> holder(String recipeId) {
		return holders.get(recipeId);
	}

	@Override
	public List<CraftRecipe> craftsFor(String item) {
		return crafts.getOrDefault(item, List.of());
	}

	@Override
	public List<SmeltRecipe> smeltsFor(String item) {
		return smelts.getOrDefault(item, List.of());
	}

	// ---- fontes naturais ------------------------------------------------------------

	private static final List<Item> PICKAXES = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
			Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE,
			Items.NETHERITE_PICKAXE);
	private static final List<Item> AXES = List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE,
			Items.IRON_AXE, Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
	private static final List<Item> SHOVELS = List.of(Items.WOODEN_SHOVEL, Items.STONE_SHOVEL,
			Items.COPPER_SHOVEL, Items.IRON_SHOVEL, Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL,
			Items.NETHERITE_SHOVEL);

	private void buildSources() {
		// Troncos: qualquer árvore serve para qualquer pedido de tronco.
		Set<String> logItems = new LinkedHashSet<>();
		Set<String> logBlocks = new LinkedHashSet<>();
		for (Holder<Block> h : BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.OVERWORLD_NATURAL_LOGS)) {
			logBlocks.add(Stacks.id(h.value()));
			logItems.add(Stacks.id(h.value().asItem()));
		}
		Source logs = new Source(logItems, logBlocks, List.of());
		for (String item : logItems) {
			sources.computeIfAbsent(item, k -> new ArrayList<>()).add(logs);
		}

		drop(Items.COBBLESTONE, Blocks.STONE);
		drop(Items.COBBLED_DEEPSLATE, Blocks.DEEPSLATE);
		drop(Items.DIRT, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.ROOTED_DIRT);
		drop(Items.SAND, Blocks.SAND);
		drop(Items.RED_SAND, Blocks.RED_SAND);
		drop(Items.GRAVEL, Blocks.GRAVEL);
		drop(Items.CLAY_BALL, Blocks.CLAY);
		drop(Items.SNOWBALL, Blocks.SNOW_BLOCK);
		drop(Items.COAL, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		drop(Items.RAW_IRON, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		drop(Items.RAW_COPPER, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		drop(Items.RAW_GOLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
		drop(Items.DIAMOND, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		drop(Items.REDSTONE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		drop(Items.LAPIS_LAZULI, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		drop(Items.EMERALD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		drop(Items.QUARTZ, Blocks.NETHER_QUARTZ_ORE);
		drop(Items.GOLD_NUGGET, Blocks.NETHER_GOLD_ORE);
		drop(Items.NETHERRACK, Blocks.NETHERRACK);
		drop(Items.BLACKSTONE, Blocks.BLACKSTONE);
		drop(Items.ANDESITE, Blocks.ANDESITE);
		drop(Items.DIORITE, Blocks.DIORITE);
		drop(Items.GRANITE, Blocks.GRANITE);
		drop(Items.TUFF, Blocks.TUFF);
		drop(Items.CALCITE, Blocks.CALCITE);
		drop(Items.OBSIDIAN, Blocks.OBSIDIAN);
		drop(Items.SUGAR_CANE, Blocks.SUGAR_CANE);
		drop(Items.PUMPKIN, Blocks.PUMPKIN);
		drop(Items.MELON_SLICE, Blocks.MELON);
		drop(Items.BAMBOO, Blocks.BAMBOO);
		drop(Items.CACTUS, Blocks.CACTUS);

		// Caça (fase 5): carne crua vira comida na fornalha.
		hunt(Items.BEEF, "cow", "mooshroom");
		hunt(Items.LEATHER, "cow", "mooshroom");
		hunt(Items.PORKCHOP, "pig");
		hunt(Items.MUTTON, "sheep");
		hunt(Stacks.item("white_wool"), "sheep");
		hunt(Items.CHICKEN, "chicken");
		hunt(Items.FEATHER, "chicken");
		hunt(Items.RABBIT, "rabbit");
		hunt(Items.STRING, "spider");
		hunt(Items.BONE, "skeleton");
		hunt(Items.ROTTEN_FLESH, "zombie");
	}

	private void drop(Item item, Block... blocks) {
		Set<String> ids = new LinkedHashSet<>();
		for (Block b : blocks) {
			ids.add(Stacks.id(b));
		}
		BlockState state = blocks[0].defaultBlockState();
		sources.computeIfAbsent(Stacks.id(item), k -> new ArrayList<>())
				.add(new Source(Set.of(Stacks.id(item)), ids, toolsFor(state)));
	}

	private void hunt(Item item, String... entities) {
		if (item == null) {
			return;
		}
		Set<String> ids = new LinkedHashSet<>();
		for (String e : entities) {
			ids.add("minecraft:" + e);
		}
		sources.computeIfAbsent(Stacks.id(item), k -> new ArrayList<>()).add(Source.hunting(Set.of(Stacks.id(item)), ids));
	}

	/** Ferramentas que soltam o drop deste bloco, da mais barata para a mais cara. */
	public static List<String> toolsFor(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) {
			return List.of();
		}
		List<Item> kind = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? PICKAXES
				: state.is(BlockTags.MINEABLE_WITH_AXE) ? AXES
				: state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? SHOVELS : PICKAXES;
		List<String> out = new ArrayList<>();
		for (Item tool : kind) {
			if (new ItemStack(tool).isCorrectToolForDrops(state)) {
				out.add(Stacks.id(tool));
			}
		}
		return out;
	}

	@Override
	public List<Source> sourcesFor(String item) {
		return sources.getOrDefault(item, List.of());
	}

	// ---- combustível --------------------------------------------------------------

	@Override
	public double fuelValue(String id) {
		Item item = Stacks.item(id);
		if (item == null) {
			return 0;
		}
		ItemStack stack = new ItemStack(item);
		if (item == Items.COAL || item == Items.CHARCOAL) {
			return 8;
		}
		if (item == Items.COAL_BLOCK) {
			return 80;
		}
		if (item == Items.BLAZE_ROD) {
			return 12;
		}
		if (item == Items.LAVA_BUCKET) {
			return 100;
		}
		if (stack.is(ItemTags.LOGS_THAT_BURN) || (stack.is(ItemTags.PLANKS) && stack.has(DataComponents.COOKING_FUEL))) {
			return 1.5;
		}
		if (item == Items.STICK) {
			return 0.5;
		}
		return stack.has(DataComponents.COOKING_FUEL) ? 1 : 0;
	}

	@Override
	public List<String> planks() {
		return planks;
	}

	@Override
	public String craftingTable() {
		return Stacks.id(Items.CRAFTING_TABLE);
	}

	@Override
	public String furnace() {
		return Stacks.id(Items.FURNACE);
	}
}

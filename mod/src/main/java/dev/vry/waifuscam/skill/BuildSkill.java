package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.craft.GameKnowledge;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.craft.Names;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Construir uma casinha 5x5 (dentro 3x3), paredes de 3 de altura, teto reto,
 * porta e tocha, como um player faz no primeiro dia.
 *
 * Escolhe um lugar plano na frente dela, junta o material que faltar (tábuas,
 * pedregulho...), entra no meio do terreno, limpa o que estiver no caminho e
 * levanta tudo de dentro: de lá alcança todos os blocos. Sai pela porta no fim.
 */
public final class BuildSkill implements Skill {
	private static final int HALF = 2; // 5x5
	private static final int WALL_HEIGHT = 3;
	private static final int TIMEOUT_TICKS = 20 * 60 * 10;
	private static final int MAX_CLEAR = 20;

	private enum Phase { SITE, MATERIAL, WALK_IN, CLEAR, PLACE, DOOR, EXIT }

	private final Choice material; // null = o bloco de construção que ela mais tem
	private final String label;
	private Phase phase = Phase.SITE;
	private BlockPos center; // pés dela, no meio da casa
	private Direction front; // lado da porta
	private final List<BlockPos> toClear = new ArrayList<>();
	private final List<BlockPos> toPlace = new ArrayList<>();
	private Item block;
	private Choice wanted;
	private Skill child;
	private Digger digger;
	private int wait;
	private int ticks;
	private int stuckPasses;
	private String failure;

	public BuildSkill(Choice material, String label) {
		this.material = material;
		this.label = label;
	}

	@Override
	public String name() {
		return "build";
	}

	@Override
	public String describe() {
		return switch (phase) {
			case MATERIAL -> "juntando material para a " + label + (child != null ? " (" + child.describe() + ")" : "");
			default -> "construindo a " + label;
		};
	}

	@Override
	public Status tick(SkillContext ctx) {
		if (++ticks > TIMEOUT_TICKS) {
			return fail("a " + label + " está demorando demais");
		}
		if (wait-- > 0) {
			return Status.RUNNING;
		}
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		switch (phase) {
			case SITE -> {
				if (!chooseSite(me, level)) {
					return fail("não achei um lugar plano para a " + label + " aqui perto");
				}
				phase = Phase.MATERIAL;
			}
			case MATERIAL -> {
				if (child != null) {
					Status s = child.tick(ctx);
					if (s == Status.RUNNING) {
						return Status.RUNNING;
					}
					child = null;
					if (s == Status.FAILED) {
						return fail("não consegui juntar material para a " + label);
					}
				}
				int need = toPlace.size();
				Choice want = materialChoice(me, ctx);
				int have = Stacks.count(me.getInventory(), want.items());
				if (have < need) {
					child = new ObtainSkill(want, need, Names.pretty(want.items().get(0)));
					return Status.RUNNING;
				}
				wanted = want;
				block = pickBlock(me, want);
				child = new GotoSkill(LevelTerrain.of(center), 0.4, label);
				phase = Phase.WALK_IN;
			}
			case WALK_IN -> {
				Status s = child.tick(ctx);
				if (s == Status.FAILED && me.blockPosition().distManhattan(center) > 2) {
					return fail("não consigo chegar no lugar da " + label);
				}
				if (s == Status.FAILED) {
					child = null;
					phase = Phase.CLEAR; // quase no meio: dá para construir daqui
					return Status.RUNNING;
				}
				if (s == Status.SUCCEEDED || me.blockPosition().equals(center)) {
					child = null;
					phase = Phase.CLEAR;
				}
			}
			case CLEAR -> {
				if (digger != null) {
					Digger.Status s = digger.tick(me, ctx.controls());
					if (s == Digger.Status.DIGGING) {
						return Status.RUNNING;
					}
					digger = null;
					wait = Human.reaction();
				}
				toClear.removeIf(p -> level.getBlockState(p).canBeReplaced());
				if (toClear.isEmpty()) {
					phase = Phase.PLACE;
				} else {
					digger = new Digger(level, toClear.remove(0), false);
				}
			}
			case PLACE -> {
				return place(me, level);
			}
			case DOOR -> {
				finishTouches(me, level);
				child = new GotoSkill(LevelTerrain.of(center.relative(front, HALF + 2)), 1.0, "fora");
				phase = Phase.EXIT;
			}
			case EXIT -> {
				Status s = child.tick(ctx);
				if (s != Status.RUNNING) {
					return Status.SUCCEEDED; // a casa já está de pé; sair é só um detalhe
				}
			}
		}
		return Status.RUNNING;
	}

	// ---- planejar ------------------------------------------------------------------

	/** Lugar plano na frente dela: chão firme, sem água, pouca coisa para limpar. */
	private boolean chooseSite(ServerPlayer me, ServerLevel level) {
		Direction facing = me.getDirection();
		BlockPos feet = me.blockPosition();
		for (int dist = 4; dist <= 10; dist += 2) {
			for (int side = 0; side <= 6; side += 3) {
				for (int sign : new int[] {1, -1}) {
					BlockPos c = feet.relative(facing, dist).relative(facing.getClockWise(), side * sign);
					if (trySite(level, c, facing.getOpposite())) {
						return true;
					}
					if (side == 0) {
						break;
					}
				}
			}
		}
		return false;
	}

	private boolean trySite(ServerLevel level, BlockPos guess, Direction door) {
		// Altura do chão em cada uma das 25 casas: aceita diferença de 1 bloco.
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int dx = -HALF; dx <= HALF; dx++) {
			for (int dz = -HALF; dz <= HALF; dz++) {
				Integer g = ground(level, guess.offset(dx, 0, dz));
				if (g == null) {
					return false;
				}
				min = Math.min(min, g);
				max = Math.max(max, g);
			}
		}
		if (max - min > 1) {
			return false;
		}
		BlockPos c = new BlockPos(guess.getX(), max + 1, guess.getZ());
		List<BlockPos> clear = new ArrayList<>();
		List<BlockPos> place = new ArrayList<>();
		for (int dy = -1; dy <= WALL_HEIGHT; dy++) {
			for (int dx = -HALF; dx <= HALF; dx++) {
				for (int dz = -HALF; dz <= HALF; dz++) {
					BlockPos p = c.offset(dx, dy, dz);
					BlockState s = level.getBlockState(p);
					boolean edge = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
					boolean doorway = isDoorway(c, door, p);
					boolean solidWanted = dy == -1 || dy == WALL_HEIGHT || (edge && !doorway);
					if (!level.getFluidState(p).isEmpty()) {
						return false;
					}
					if (solidWanted) {
						if (s.canBeReplaced()) {
							place.add(p);
						}
					} else if (!s.canBeReplaced()) {
						clear.add(p); // pedra, tronco, o que for: ela tira (até o limite)
					}
				}
			}
		}
		if (clear.size() > MAX_CLEAR) {
			return false;
		}
		for (BlockPos p : clear) {
			if (level.getBlockState(p).getDestroySpeed(level, p) < 0) {
				return false;
			}
		}
		center = c;
		front = door;
		toClear.clear();
		toClear.addAll(clear);
		toPlace.clear();
		toPlace.addAll(place);
		// De baixo para cima; no teto, das bordas para o meio (sempre tem onde apoiar).
		toPlace.sort(Comparator.comparingInt((BlockPos p) -> p.getY())
				.thenComparingInt(p -> -Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ()))));
		return true;
	}

	/** Os dois blocos da porta, no meio da parede da frente. */
	private static boolean isDoorway(BlockPos c, Direction door, BlockPos p) {
		BlockPos bottom = c.relative(door, HALF);
		return p.equals(bottom) || p.equals(bottom.above());
	}

	/** Y do chão firme numa coluna (perto da altura dela), ou null se for água/buraco. */
	private static Integer ground(ServerLevel level, BlockPos column) {
		for (int dy = 3; dy >= -3; dy--) {
			BlockPos p = column.offset(0, dy, 0);
			BlockState s = level.getBlockState(p);
			if (!level.getFluidState(p).isEmpty()) {
				return null;
			}
			if (!s.canBeReplaced() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS) && s.isFaceSturdy(level, p, Direction.UP)) {
				return p.getY();
			}
		}
		return null;
	}

	// ---- material ------------------------------------------------------------------

	/** Material pedido, ou o que ela mais tem entre tábuas e pedra; nada = tábuas. */
	private Choice materialChoice(ServerPlayer me, SkillContext ctx) {
		if (material != null) {
			return material;
		}
		GameKnowledge k = GameKnowledge.of(ctx.server());
		Choice planks = new Choice(k.planks());
		Choice stone = Choice.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone_bricks");
		int p = Stacks.count(me.getInventory(), planks.items());
		int s = Stacks.count(me.getInventory(), stone.items());
		return s > p ? stone : planks;
	}

	private static Item pickBlock(ServerPlayer me, Choice want) {
		Item best = null;
		int most = 0;
		for (String id : want.items()) {
			Item item = Stacks.item(id);
			if (!(item instanceof BlockItem)) {
				continue;
			}
			int n = Stacks.count(me.getInventory(), s -> s.is(item));
			if (n > most) {
				most = n;
				best = item;
			}
		}
		return best;
	}

	// ---- levantar ------------------------------------------------------------------

	private Status place(ServerPlayer me, ServerLevel level) {
		toPlace.removeIf(p -> !level.getBlockState(p).canBeReplaced());
		if (toPlace.isEmpty()) {
			phase = Phase.DOOR;
			return Status.RUNNING;
		}
		if (block == null || Stacks.find(me.getInventory(), s -> s.is(block)) < 0) {
			block = pickBlock(me, wanted); // acabou um tipo de tábua: usa o outro
			if (block == null) {
				return fail("acabou o material da " + label);
			}
		}
		for (BlockPos p : toPlace) {
			if (Placer.supportFor(level, p) == null || !me.isWithinBlockInteractionRange(p, 0.5)) {
				continue;
			}
			if (block != null && Placer.placeAgainst(me, block, p)) {
				toPlace.remove(p);
				stuckPasses = 0;
				wait = Human.reaction() / 2;
				return Status.RUNNING;
			}
		}
		if (++stuckPasses > 40) {
			return fail("não consegui terminar a " + label + " (faltam " + toPlace.size() + " blocos)");
		}
		return Status.RUNNING;
	}

	/** Porta (se tiver ou der para fazer na hora) e uma tocha lá dentro. */
	private void finishTouches(ServerPlayer me, ServerLevel level) {
		BlockPos doorway = center.relative(front, HALF);
		int doorSlot = Stacks.find(me.getInventory(), s -> s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS));
		if (doorSlot >= 0) {
			ItemStack door = me.getInventory().getItem(doorSlot);
			me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
					net.minecraft.world.phys.Vec3.atCenterOf(doorway));
			Placer.place(me, door.getItem(), doorway);
		}
		if (Stacks.find(me.getInventory(), s -> s.is(Items.TORCH)) >= 0) {
			BlockPos corner = center.relative(front.getOpposite()).relative(front.getClockWise());
			Placer.place(me, Items.TORCH, corner);
		}
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

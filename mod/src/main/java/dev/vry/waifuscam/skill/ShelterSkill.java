package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Passar a noite em segurança: cava um buraco de 3 blocos, fecha em cima, espera
 * amanhecer, abre e sobe tapando o buraco de novo (o terreno fica como estava).
 *
 * Se tiver cama por perto, dorme nela. Se ela estiver num chão construído (casa
 * de alguém), não cava: só espera ali.
 */
public final class ShelterSkill implements Skill {
	private static final int DEPTH = 3;
	private static final List<Item> FILLERS = List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
			Items.NETHERRACK, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF,
			Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS);

	private enum Phase { START, MOVE, BED, DIG, LAND, SEAL, WAIT, OPEN, PILLAR }

	private Phase phase = Phase.START;
	private int topY;
	private int dug;
	private Digger digger;
	private int phaseTicks;
	private BlockPos seal;
	private int pillarTarget;
	private Skill bed;
	private Skill walk;
	private int moves;
	private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private String failure;

	@Override
	public String name() {
		return "shelter";
	}

	@Override
	public String describe() {
		return switch (phase) {
			case BED -> bed != null ? bed.describe() : "indo dormir";
			case WAIT -> "esperando amanhecer no abrigo";
			case OPEN, PILLAR -> "saindo do abrigo";
			default -> "fazendo um abrigo para a noite";
		};
	}

	/** Anoitecendo, noite ou tempestade: hora de monstro (ou quase). Termina ao amanhecer. */
	public static boolean dangerous(ServerLevel level) {
		long t = level.getServer().overworld().getOverworldClockTime() % 24000L;
		return (t >= 11800 && t < 23400) || level.isThundering();
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		phaseTicks++;
		switch (phase) {
			case START -> {
				if (level.dimension() != Level.OVERWORLD) {
					return Status.SUCCEEDED; // no Nether não tem dia e noite
				}
				if (!dangerous(level)) {
					return Status.SUCCEEDED;
				}
				// Cama por perto é melhor que buraco (fase 7).
				if (SleepSkill.nearestBed(me, java.util.Set.of()) != null) {
					bed = new SleepSkill();
					next(Phase.BED);
					return Status.RUNNING;
				}
				if (!level.canSeeSky(me.blockPosition().above())) {
					next(Phase.WAIT); // tem teto em cima: dentro de casa ou de uma caverna coberta
					return Status.RUNNING;
				}
				BlockState floor = level.getBlockState(me.blockPosition().below());
				if (!natural(floor) || !safeColumn(level, me.blockPosition())) {
					// Chão construído (não cava no chão dos outros) ou com caverna/água embaixo: procura outro lugar.
					return relocate(ctx);
				}
				topY = me.getBlockY();
				next(Phase.DIG);
			}
			case MOVE -> {
				Status s = walk.tick(ctx);
				if (s != Status.RUNNING) {
					walk = null;
					next(Phase.START);
				}
			}
			case BED -> {
				Status s = bed.tick(ctx);
				if (s == Status.SUCCEEDED) {
					return Status.SUCCEEDED;
				}
				if (s == Status.FAILED) {
					bed = null;
					// Não deu para dormir: volta para o plano do buraco.
					BlockState floor = level.getBlockState(me.blockPosition().below());
					topY = me.getBlockY();
					next(natural(floor) ? Phase.DIG : Phase.WAIT);
				}
			}
			case DIG -> {
				if (digger == null) {
					BlockPos target = me.blockPosition().below();
					BlockState s = level.getBlockState(target);
					BlockState under = level.getBlockState(target.below());
					boolean unsafe = BlockFinder.nextToFluid(level, target) || !level.getFluidState(target).isEmpty()
							|| under.isAir() || !under.getFluidState().isEmpty() || s.getDestroySpeed(level, target) < 0;
					if (unsafe) {
						// Não dá para cavar mais aqui; se já cavou um pouco, fecha assim mesmo.
						if (dug >= 2) {
							next(Phase.SEAL);
							return Status.RUNNING;
						}
						if (dug == 0) {
							return relocate(ctx);
						}
						return fail("o chão aqui não é seguro para cavar");
					}
					digger = new Digger(level, target, false); // sem picareta também cava (só não pega a pedra)
				}
				ctx.controls().lookAt(Vec3.atCenterOf(digger.pos()));
				Digger.Status s = digger.tick(me, ctx.controls());
				if (s == Digger.Status.FAILED) {
					return fail("não consegui cavar o abrigo: " + digger.failure());
				}
				if (s == Digger.Status.DONE) {
					digger = null;
					dug++;
					next(Phase.LAND);
				}
			}
			case LAND -> {
				if (me.onGround() && me.getBlockY() <= topY - dug) {
					next(dug >= DEPTH ? Phase.SEAL : Phase.DIG);
				} else if (phaseTicks > 40) {
					next(dug >= DEPTH ? Phase.SEAL : Phase.DIG);
				}
			}
			case SEAL -> {
				seal = me.blockPosition().above(2);
				if (!level.getBlockState(seal).isAir() && !level.getBlockState(seal).canBeReplaced()) {
					next(Phase.WAIT); // já está fechado
					return Status.RUNNING;
				}
				if (placeAgainstWall(me, seal)) {
					next(Phase.WAIT);
				} else if (phaseTicks > 20) {
					next(Phase.WAIT); // sem bloco para fechar: espera no buraco mesmo
					seal = null;
				}
			}
			case WAIT -> {
				ctx.controls().lookAt(me.getEyePosition().add(me.getLookAngle().multiply(1, 0, 1)));
				if (!dangerous(level)) {
					next(dug > 0 ? Phase.OPEN : null);
					if (dug == 0) {
						return Status.SUCCEEDED;
					}
				}
			}
			case OPEN -> {
				if (seal == null || level.getBlockState(seal).isAir()) {
					pillarTarget = me.getBlockY();
					next(Phase.PILLAR);
					return Status.RUNNING;
				}
				if (digger == null) {
					digger = new Digger(level, seal);
				}
				Digger.Status s = digger.tick(me, ctx.controls());
				if (s != Digger.Status.DIGGING) {
					digger = null;
					if (s == Digger.Status.FAILED) {
						return fail("não consegui abrir o abrigo");
					}
				}
			}
			case PILLAR -> {
				if (me.getBlockY() >= topY) {
					return Status.SUCCEEDED;
				}
				if (phaseTicks > 20 * 20) {
					return fail("não consegui sair do buraco");
				}
				int slot = filler(me.getInventory());
				if (slot < 0) {
					return fail("fiquei sem bloco para subir do buraco");
				}
				if (me.onGround()) {
					pillarTarget = me.getBlockY();
					ctx.controls().jump(true);
					ctx.controls().lookAt(me.position().add(0, -1, 0));
				} else if (me.getY() >= pillarTarget + 1.05) {
					Stacks.hold(me.getInventory(), slot);
					Item item = me.getMainHandItem().getItem();
					Placer.place(me, item, new BlockPos(me.getBlockX(), pillarTarget, me.getBlockZ()));
				}
			}
		}
		return failure == null ? Status.RUNNING : Status.FAILED;
	}

	private void next(Phase p) {
		if (p != null) {
			phase = p;
		}
		phaseTicks = 0;
	}

	private static boolean natural(BlockState s) {
		return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND)
				|| s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(BlockTags.SNOW)
				|| s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.world.level.block.Blocks.CLAY)
				|| s.is(net.minecraft.world.level.block.Blocks.MUD) || s.is(BlockTags.TERRACOTTA);
	}

	/** Dá para cavar 3 blocos aqui sem cair numa caverna, em água ou lava? */
	private static boolean safeColumn(ServerLevel level, BlockPos feet) {
		for (int d = 1; d <= DEPTH + 1; d++) {
			BlockPos p = feet.below(d);
			BlockState s = level.getBlockState(p);
			if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(level, p) < 0 || BlockFinder.nextToFluid(level, p)) {
				return false;
			}
		}
		return true;
	}

	/** Anda até um lugar perto com chão natural e seguro para cavar. */
	private Status relocate(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		badSpots.add(me.blockPosition());
		if (++moves > 4) {
			// Sem lugar para cavar: espera aqui mesmo (os reflexos cuidam dos monstros).
			next(Phase.WAIT);
			return Status.RUNNING;
		}
		java.util.List<BlockPos> spots = BlockFinder.find(level, me.blockPosition(), 10, 12, p -> {
			BlockPos feet = p.above();
			return natural(level.getBlockState(p)) && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
					&& level.canSeeSky(feet) && !badSpots.contains(feet) && safeColumn(level, feet);
		});
		if (spots.isEmpty()) {
			next(Phase.WAIT);
			return Status.RUNNING;
		}
		walk = new GotoSkill(dev.vry.waifuscam.pathing.LevelTerrain.of(spots.get(0).above()), 0.5, "lugar do abrigo");
		next(Phase.MOVE);
		return Status.RUNNING;
	}

	static int filler(Inventory inv) {
		for (Item item : FILLERS) {
			int slot = Stacks.find(inv, s -> s.is(item));
			if (slot >= 0) {
				return slot;
			}
		}
		return Stacks.find(inv, s -> s.getItem() instanceof BlockItem b
				&& b.getBlock().defaultBlockState().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
				&& !(b.getBlock() instanceof net.minecraft.world.level.block.FallingBlock));
	}

	/** Põe um bloco em `pos` clicando na lateral de uma parede vizinha. */
	private static boolean placeAgainstWall(ServerPlayer me, BlockPos pos) {
		ServerLevel level = me.level();
		int slot = filler(me.getInventory());
		if (slot < 0) {
			return false;
		}
		Stacks.hold(me.getInventory(), slot);
		ItemStack stack = me.getMainHandItem();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos wall = pos.relative(d);
			if (!level.getBlockState(wall).isFaceSturdy(level, wall, d.getOpposite())) {
				continue;
			}
			Vec3 hit = Vec3.atCenterOf(wall).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
			me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
			me.gameMode.useItemOn(me, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, d.getOpposite(), wall, false));
			if (!level.getBlockState(pos).isAir()) {
				me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				return true;
			}
		}
		return false;
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return true;
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

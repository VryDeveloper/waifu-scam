package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dormir numa cama por perto até amanhecer: anda até ela e clica, como um
 * player. Se a cama estiver ocupada ou tiver monstro perto, tenta outra.
 */
public final class SleepSkill implements Skill {
	public static final int SEARCH_RADIUS = 24;
	private static final int TRY_TICKS = 20;

	private final Set<BlockPos> tried = new HashSet<>();
	private BlockPos bed;
	private Skill walk;
	private int clickTicks = -1;
	private boolean slept;
	private String failure;

	@Override
	public String name() {
		return "sleep";
	}

	@Override
	public String describe() {
		return slept ? "dormindo" : "indo dormir";
	}

	/** Cama livre mais perto, ou null. */
	public static BlockPos nearestBed(ServerPlayer me, Set<BlockPos> skip) {
		List<BlockPos> beds = BlockFinder.find(me.level(), me.blockPosition(), SEARCH_RADIUS, 6, p -> {
			BlockState s = me.level().getBlockState(p);
			return s.is(BlockTags.BEDS) && !s.getValue(BedBlock.OCCUPIED) && !skip.contains(p.immutable());
		});
		return beds.isEmpty() ? null : beds.get(0);
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (slept) {
			return me.isSleeping() ? Status.RUNNING : Status.SUCCEEDED; // o jogo acorda ela de manhã
		}
		if (me.isSleeping()) {
			slept = true;
			return Status.RUNNING;
		}
		if (bed == null) {
			bed = nearestBed(me, tried);
			walk = null;
			clickTicks = -1;
			if (bed == null) {
				return fail(tried.isEmpty() ? "não tem cama por perto" : "não consegui deitar em nenhuma cama");
			}
		}
		if (!me.isWithinBlockInteractionRange(bed, 0)) {
			if (walk == null) {
				walk = new GotoSkill(LevelTerrain.of(bed), 2.0, "cama");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				tried.add(bed);
				bed = null;
			}
			return Status.RUNNING;
		}
		if (clickTicks < 0) {
			Vec3 hit = Vec3.atCenterOf(bed).add(0, 0.06, 0);
			ctx.controls().lookAt(hit);
			me.gameMode.useItemOn(me, me.level(), me.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, Direction.UP, bed, false));
			clickTicks = 0;
		} else if (++clickTicks > TRY_TICKS) {
			// Não deitou (monstro perto, ainda não é noite, cama de outro): tenta outra.
			tried.add(bed);
			bed = null;
		}
		return Status.RUNNING;
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

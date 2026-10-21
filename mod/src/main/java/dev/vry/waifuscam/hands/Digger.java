package dev.vry.waifuscam.hands;

import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.control.Human;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Quebra um bloco do jeito que um player quebra: segura o botão, o bloco racha
 * aos poucos conforme a ferramenta, e o jogo solta o drop normal.
 *
 * Adaptado do EntityPlayerActionPack do Carpet (MIT, gnembon).
 */
public final class Digger {
	public enum Status { DIGGING, DONE, FAILED }

	private static final int TIMEOUT_TICKS = 20 * 30;

	private final BlockPos pos;
	private final Block block;
	private float progress;
	private boolean started;
	private int ticks;
	private int wait = Human.reaction();
	private String failure;

	private final boolean needDrops;

	public Digger(ServerLevel level, BlockPos pos) {
		this(level, pos, true);
	}

	/** needDrops = false: quebra mesmo sem a ferramenta certa (cavar abrigo, abrir caminho). */
	public Digger(ServerLevel level, BlockPos pos, boolean needDrops) {
		this.pos = pos.immutable();
		this.block = level.getBlockState(pos).getBlock();
		this.needDrops = needDrops;
	}

	public BlockPos pos() {
		return pos;
	}

	public String failure() {
		return failure;
	}

	public Status tick(ServerPlayer player, Controls controls) {
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.getBlock() != block) {
			return Status.DONE; // quebrou (ou alguém quebrou antes)
		}
		if (!player.isWithinBlockInteractionRange(pos, 0.5)) {
			return fail(player, "fiquei longe demais do bloco");
		}
		if (++ticks > TIMEOUT_TICKS) {
			return fail(player, "esse bloco não quebra");
		}
		if (!Tools.equipFor(player, state) && needDrops) {
			return fail(player, "não tenho ferramenta para isso");
		}
		controls.lookAt(Vec3.atCenterOf(pos));
		Direction side = face(player, pos);
		int maxY = level.getMaxY();

		// Mira e reage antes de começar a bater, como uma pessoa.
		if (!started && (wait-- > 0 || !Controls.aimed(player, Vec3.atCenterOf(pos), 25))) {
			return Status.DIGGING;
		}
		if (!started) {
			started = true;
			player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, maxY, -1);
			state.attack(level, pos, player);
			progress = state.getDestroyProgress(player, level, pos);
			if (progress >= 1 || level.getBlockState(pos).isAir()) {
				return Status.DONE; // quebra na hora (grama, flores, tocha)
			}
		} else {
			progress += state.getDestroyProgress(player, level, pos);
			if (progress >= 1) {
				player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, maxY, -1);
				level.destroyBlockProgress(player.getId(), pos, -1);
				player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				return level.getBlockState(pos).getBlock() == block ? fail(player, "o jogo não deixou quebrar") : Status.DONE;
			}
			level.destroyBlockProgress(player.getId(), pos, (int) (progress * 10));
		}
		player.resetLastActionTime();
		if (ticks % 4 == 1) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		}
		return Status.DIGGING;
	}

	/** Para no meio (outra ação, perigo). */
	public void abort(ServerPlayer player) {
		if (started) {
			ServerLevel level = player.level();
			player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.DOWN, level.getMaxY(), -1);
			level.destroyBlockProgress(player.getId(), pos, -1);
			started = false;
		}
	}

	private Status fail(ServerPlayer player, String reason) {
		abort(player);
		failure = reason;
		return Status.FAILED;
	}

	/** A face do bloco virada para ela. */
	static Direction face(ServerPlayer player, BlockPos pos) {
		Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(d.x, d.y, d.z);
	}
}

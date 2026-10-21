package dev.vry.waifuscam.hands;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Colocar blocos como um player: item na mão, clique direito numa face. */
public final class Placer {
	private Placer() {}

	/** O bloco pode ir em target, apoiado no bloco de baixo, sem prender ninguém? */
	public static boolean canPlaceAt(ServerLevel level, BlockPos target, ServerPlayer player) {
		BlockState here = level.getBlockState(target);
		if (!here.canBeReplaced() || !here.getFluidState().isEmpty()) {
			return false;
		}
		BlockPos below = target.below();
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
			return false;
		}
		if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(target)).isEmpty()) {
			return false; // alguém (ou ela) está em pé ali
		}
		return player.isWithinBlockInteractionRange(target, 0);
	}

	/**
	 * Coloca o item (um bloco) numa posição livre perto dela. Devolve onde colocou,
	 * ou null se não deu.
	 */
	public static BlockPos placeNear(ServerPlayer player, Item item) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		for (int r = 1; r <= 3; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy : new int[] {0, -1, 1}) {
						BlockPos target = feet.offset(dx, dy, dz);
						if (canPlaceAt(level, target, player) && place(player, item, target)) {
							return target;
						}
					}
				}
			}
		}
		return null;
	}

	/** Coloca o item em target, clicando no topo do bloco de baixo. */
	public static boolean place(ServerPlayer player, Item item, BlockPos target) {
		if (!(item instanceof BlockItem blockItem)) {
			return false;
		}
		Inventory inv = player.getInventory();
		int slot = Stacks.find(inv, s -> s.is(item));
		if (slot < 0) {
			return false;
		}
		Stacks.hold(inv, slot);
		ServerLevel level = player.level();
		BlockPos below = target.below();
		Vec3 hit = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, Direction.UP, below, false));
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		}
		return level.getBlockState(target).is(blockItem.getBlock());
	}

	/**
	 * Coloca o bloco em target apoiando em qualquer bloco firme do lado (como um
	 * player faz com teto e parede): clica na face do vizinho virada para target.
	 * Tenta o apoio de baixo primeiro. Devolve true se o bloco ficou lá.
	 */
	public static boolean placeAgainst(ServerPlayer player, Item item, BlockPos target) {
		if (!(item instanceof BlockItem blockItem)) {
			return false;
		}
		ServerLevel level = player.level();
		BlockState here = level.getBlockState(target);
		if (!here.canBeReplaced() || !level.getEntitiesOfClass(LivingEntity.class, new AABB(target)).isEmpty()) {
			return false;
		}
		Direction support = supportFor(level, target);
		if (support == null) {
			return false;
		}
		Inventory inv = player.getInventory();
		int slot = Stacks.find(inv, s -> s.is(item));
		if (slot < 0) {
			return false;
		}
		Stacks.hold(inv, slot);
		BlockPos neighbor = target.relative(support);
		Direction face = support.getOpposite();
		Vec3 hit = Vec3.atCenterOf(neighbor).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, face, neighbor, false));
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		}
		return level.getBlockState(target).is(blockItem.getBlock());
	}

	/** Direção de um vizinho firme onde dá para apoiar o bloco (baixo primeiro), ou null. */
	public static Direction supportFor(ServerLevel level, BlockPos target) {
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction d : order) {
			BlockPos n = target.relative(d);
			BlockState s = level.getBlockState(n);
			if (!s.isAir() && !s.canBeReplaced() && !s.getCollisionShape(level, n).isEmpty()) {
				return d;
			}
		}
		return null;
	}
}

package dev.vry.waifuscam.hands;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Procura blocos em volta dela, do mais perto para o mais longe. */
public final class BlockFinder {
	private BlockFinder() {}

	/**
	 * Até `limit` blocos que passam no filtro, num cubo de raio `radius`
	 * (metade disso na vertical). Só olha chunks carregados.
	 */
	public static List<BlockPos> find(ServerLevel level, BlockPos center, int radius, int limit, Predicate<BlockPos> filter) {
		List<BlockPos> found = new ArrayList<>();
		int vertical = Math.max(4, radius / 2);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		// Em cascas de raio crescente: para cedo quando acha o bastante por perto.
		for (int r = 0; r <= radius && found.size() < limit; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue; // só a borda desta casca
					}
					for (int dy = -vertical; dy <= vertical; dy++) {
						p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (p.getY() < level.getMinY() || p.getY() > level.getMaxY() || !level.isLoaded(p)) {
							continue;
						}
						if (filter.test(p)) {
							found.add(p.immutable());
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(b -> b.distSqr(center)));
		return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
	}

	/** Tem pelo menos uma face encostada em ar (dá para alcançar sem cavar até ele). */
	public static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.isAir() || (n.getCollisionShape(level, pos.relative(d)).isEmpty() && n.getFluidState().isEmpty())) {
				return true;
			}
		}
		return false;
	}

	/** Quebrar aqui solta lava ou água em cima dela? */
	public static boolean nextToFluid(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) {
				continue;
			}
			FluidState f = level.getFluidState(pos.relative(d));
			if (!f.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tronco de árvore de verdade: tem folhas naturais (não colocadas por player)
	 * por perto. Evita desmontar casas de madeira.
	 */
	public static boolean naturalTree(ServerLevel level, BlockPos log) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -1; dy <= 8; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					p.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
					BlockState s = level.getBlockState(p);
					if (s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}

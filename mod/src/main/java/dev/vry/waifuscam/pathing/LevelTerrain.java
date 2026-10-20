package dev.vry.waifuscam.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * Terrain lido do mundo de verdade. Cada busca usa uma instância nova, que guarda
 * os blocos consultados (o A* pergunta o mesmo bloco várias vezes).
 */
public final class LevelTerrain implements Terrain {
	/** Abaixo disso o bloco não atrapalha andar (tapete, camada fina de neve). */
	private static final double LOW_SHAPE = 0.2;

	private final ServerLevel level;
	private final Map<Pos, BlockState> cache = new HashMap<>();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	public LevelTerrain(ServerLevel level) {
		this.level = level;
	}

	private BlockState state(Pos p) {
		return cache.computeIfAbsent(p, k -> level.getBlockState(cursor.set(k.x(), k.y(), k.z())));
	}

	private VoxelShape shape(Pos p) {
		return state(p).getCollisionShape(level, cursor.set(p.x(), p.y(), p.z()));
	}

	@Override
	public boolean isLoaded(Pos p) {
		return p.y() >= level.getMinY() && p.y() < level.getMaxY() && level.isLoaded(cursor.set(p.x(), p.y(), p.z()));
	}

	@Override
	public boolean isSolid(Pos p) {
		VoxelShape s = shape(p);
		if (s.isEmpty()) {
			return false;
		}
		double top = s.max(Direction.Axis.Y);
		// Cerca, muro e portão têm 1,5 de altura: não dá para ficar em cima andando.
		return top >= 0.5 && top <= 1.0;
	}

	@Override
	public boolean isPassable(Pos p) {
		VoxelShape s = shape(p);
		return s.isEmpty() || s.max(Direction.Axis.Y) <= LOW_SHAPE;
	}

	@Override
	public boolean isWater(Pos p) {
		return state(p).getFluidState().is(FluidTags.WATER);
	}

	@Override
	public boolean isDangerous(Pos p) {
		BlockState s = state(p);
		return s.getFluidState().is(FluidTags.LAVA)
				|| s.is(BlockTags.FIRE)
				|| s.is(BlockTags.CAMPFIRES)
				|| s.is(Blocks.CACTUS)
				|| s.is(Blocks.MAGMA_BLOCK)
				|| s.is(Blocks.SWEET_BERRY_BUSH)
				|| s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.WITHER_ROSE)
				|| s.is(Blocks.COBWEB);
	}

	public static Pos of(BlockPos b) {
		return new Pos(b.getX(), b.getY(), b.getZ());
	}
}

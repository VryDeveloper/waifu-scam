package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ponte reta para onde ela está olhando: agachada na beirada, coloca um bloco
 * na frente, embaixo, e anda um passo. Para quando chega em chão firme do outro
 * lado (ou no tamanho máximo). Serve para atravessar rio, buraco e lava.
 */
public final class BridgeSkill implements Skill {
	private static final List<String> BLOCKS = List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
			"minecraft:dirt", "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
			"minecraft:stone", "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite", "minecraft:granite");
	private static final int TIMEOUT_TICKS = 20 * 60 * 4;

	private final int maxLength;
	private Direction dir;
	private BlockPos startFeet;
	private int placed;
	private int solidAhead;
	private int ticks;
	private int wait;
	private int stepTicks;
	private BlockPos stepFrom;
	private Skill gather;
	private String failure;

	/** dir = null: para onde ela estiver olhando. */
	public BridgeSkill(int maxLength, Direction dir) {
		this.maxLength = Math.max(2, Math.min(64, maxLength));
		this.dir = dir;
	}

	@Override
	public String name() {
		return "bridge";
	}

	@Override
	public String describe() {
		return gather != null ? "juntando blocos para a ponte" : "fazendo uma ponte";
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		if (++ticks > TIMEOUT_TICKS) {
			return fail("a ponte está demorando demais");
		}
		if (gather != null) {
			Status s = gather.tick(ctx);
			if (s == Status.RUNNING) {
				return Status.RUNNING;
			}
			gather = null;
			if (s == Status.FAILED) {
				return fail("não tenho blocos para a ponte");
			}
		}
		if (startFeet == null) {
			dir = dir != null ? dir : me.getDirection();
			startFeet = me.blockPosition();
		}
		Item block = buildingBlock(me);
		if (block == null) {
			if (placed > 0) {
				return fail("acabaram os blocos no meio da ponte");
			}
			gather = new ObtainSkill(new Choice(BLOCKS), 24, "pedregulho");
			return Status.RUNNING;
		}
		if (wait-- > 0) {
			ctx.controls().sneak(true);
			return Status.RUNNING;
		}

		BlockPos feet = me.blockPosition();
		if (stepFrom != null) {
			// andando um passo, agachada (não cai da beirada)
			if (!feet.equals(stepFrom)) {
				stepFrom = null;
			} else if (++stepTicks > 40) {
				return fail("tem alguma coisa no caminho da ponte");
			} else {
				ctx.controls().sneak(true).forward(1);
				ctx.controls().lookAt(Vec3.atCenterOf(feet.relative(dir, 3)).add(0, 0.5, 0));
				return Status.RUNNING;
			}
		}
		if (feet.distManhattan(startFeet) >= maxLength) {
			return Status.SUCCEEDED;
		}
		BlockPos ahead = feet.relative(dir);
		BlockPos below = ahead.below();
		if (!level.getBlockState(ahead).canBeReplaced() || !level.getBlockState(ahead.above()).canBeReplaced()) {
			return placed > 0 ? Status.SUCCEEDED : fail("tem parede na frente, não precisa de ponte");
		}
		boolean gap = level.getBlockState(below).canBeReplaced();
		if (gap) {
			solidAhead = 0;
			ctx.controls().sneak(true);
			ctx.controls().lookAt(Vec3.atCenterOf(below));
			if (!Placer.placeAgainst(me, block, below)) {
				return fail("não consegui colocar o bloco da ponte");
			}
			placed++;
			wait = Human.reaction() / 2;
			return Status.RUNNING;
		}
		if (placed > 0 && ++solidAhead >= 2) {
			return Status.SUCCEEDED; // chegou do outro lado
		}
		stepFrom = feet;
		stepTicks = 0;
		return Status.RUNNING;
	}

	private static Item buildingBlock(ServerPlayer me) {
		for (String id : BLOCKS) {
			Item item = Stacks.item(id);
			if (item instanceof BlockItem && Stacks.find(me.getInventory(), s -> s.is(item)) >= 0) {
				return item;
			}
		}
		return null;
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

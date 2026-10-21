package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Pescar como um player: vara na mão, vai para a beira da água, joga a linha,
 * espera a boia afundar e puxa. Repete até pegar `count` coisas (peixe, às
 * vezes um tesouro). Sem vara, faz uma antes (linha de aranha + gravetos).
 */
public final class FishSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 60 * 6;
	private static final int MAX_WAIT_PER_CAST = 20 * 45;

	private enum Phase { ROD, FIND_WATER, WALK, CAST, WAIT, REEL }

	private final int count;
	private Phase phase = Phase.ROD;
	private Skill child;
	private BlockPos water;
	private int caught;
	private int ticks;
	private int waitTicks;
	private int settle;
	private String failure;

	public FishSkill(int count) {
		this.count = Math.max(1, Math.min(32, count));
	}

	@Override
	public String name() {
		return "fish";
	}

	@Override
	public String describe() {
		return "pescando" + (caught > 0 ? " (" + caught + "/" + count + ")" : "");
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		if (++ticks > TIMEOUT_TICKS) {
			reelIn(me);
			return caught > 0 ? Status.SUCCEEDED : fail("os peixes não morderam");
		}
		if (child != null) {
			Status s = child.tick(ctx);
			if (s == Status.RUNNING) {
				return Status.RUNNING;
			}
			child = null;
			if (s == Status.FAILED && phase == Phase.ROD) {
				return fail("não consegui fazer uma vara de pesca");
			}
			if (s == Status.FAILED && phase == Phase.WALK) {
				phase = Phase.FIND_WATER;
				return Status.RUNNING;
			}
		}
		switch (phase) {
			case ROD -> {
				if (Stacks.find(me.getInventory(), st -> st.is(Items.FISHING_ROD)) < 0) {
					child = new ObtainSkill(Choice.of("minecraft:fishing_rod"), 1, "vara de pesca");
					return Status.RUNNING;
				}
				phase = Phase.FIND_WATER;
			}
			case FIND_WATER -> {
				// Água com pelo menos 2x2 de superfície, de preferência funda (rende mais).
				List<BlockPos> found = BlockFinder.find(level, me.blockPosition(), 24, 8, p ->
						level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p).isSource()
								&& level.getBlockState(p.above()).isAir()
								&& level.getFluidState(p.below()).is(FluidTags.WATER));
				if (found.isEmpty()) {
					return fail("não achei água boa para pescar por perto");
				}
				water = found.get(0);
				if (me.getEyePosition().distanceTo(Vec3.atCenterOf(water)) < 9) {
					phase = Phase.CAST;
				} else {
					child = new GotoSkill(LevelTerrain.of(water), 5.0, "água");
					phase = Phase.WALK;
				}
			}
			case WALK -> phase = Phase.CAST;
			case CAST -> {
				hold(me);
				ctx.controls().lookAt(Vec3.atCenterOf(water).add(0, 0.6, 0));
				if (!dev.vry.waifuscam.control.Controls.aimed(me, Vec3.atCenterOf(water).add(0, 0.6, 0), 8)) {
					return Status.RUNNING;
				}
				if (me.fishing == null) {
					use(me);
				}
				waitTicks = 0;
				settle = 0;
				phase = Phase.WAIT;
			}
			case WAIT -> {
				FishingHook hook = me.fishing;
				if (hook == null || !hook.isAlive()) {
					phase = Phase.CAST; // a linha voltou (enroscou, caiu longe): joga de novo
					return Status.RUNNING;
				}
				ctx.controls().lookAt(hook.position());
				me.resetLastActionTime();
				if (++waitTicks > MAX_WAIT_PER_CAST) {
					reelIn(me);
					phase = Phase.CAST;
					return Status.RUNNING;
				}
				// A boia assenta na água; quando o peixe morde, ela afunda de repente.
				boolean inWater = level.getFluidState(hook.blockPosition()).is(FluidTags.WATER) || hook.isInWater();
				if (!inWater) {
					if (hook.onGround() || waitTicks > 60) {
						reelIn(me); // caiu no chão: tenta de novo
						phase = Phase.CAST;
					}
					return Status.RUNNING;
				}
				if (++settle > 40 && hook.getDeltaMovement().y < -0.12) {
					phase = Phase.REEL;
				}
			}
			case REEL -> {
				reelIn(me);
				phase = Phase.CAST;
				caught++; // puxou com o peixe mordendo: veio algo (peixe, tesouro ou lixo)
				if (caught >= count) {
					return Status.SUCCEEDED;
				}
			}
		}
		return Status.RUNNING;
	}

	private static void hold(ServerPlayer me) {
		int slot = Stacks.find(me.getInventory(), st -> st.is(Items.FISHING_ROD));
		if (slot >= 0) {
			Stacks.hold(me.getInventory(), slot);
		}
	}

	private static void use(ServerPlayer me) {
		me.gameMode.useItem(me, me.level(), me.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
		me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
	}

	/** Puxa a linha (o mesmo clique que joga). */
	private static void reelIn(ServerPlayer me) {
		if (me.fishing != null) {
			hold(me);
			use(me);
		}
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return true; // pescar é ficar parada esperando
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

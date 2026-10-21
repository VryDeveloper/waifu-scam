package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * Sair de um buraco (o abrigo da noite, um poço em que caiu): quebra o que
 * estiver em cima da cabeça e sobe pulando e colocando bloco embaixo dos pés.
 */
public final class EscapeSkill implements Skill {
	private static final int MAX_TICKS = 20 * 30;

	private Digger digger;
	private int ticks;
	private int pillarY = Integer.MIN_VALUE;
	private String failure;

	@Override
	public String name() {
		return "escape";
	}

	@Override
	public String describe() {
		return "saindo do buraco";
	}

	/** Paredes dos quatro lados, na altura dos pés e da cabeça: não dá para sair andando. */
	public static boolean trapped(ServerPlayer me) {
		ServerLevel level = me.level();
		BlockPos feet = me.blockPosition();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(d);
			boolean low = !level.getBlockState(side).getCollisionShape(level, side).isEmpty();
			boolean high = !level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty();
			if (!low || !high) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		ServerLevel level = ctx.level();
		if (!trapped(me) && me.onGround()) {
			return Status.SUCCEEDED;
		}
		if (++ticks > MAX_TICKS) {
			return fail("não consegui sair do buraco");
		}
		if (digger != null) {
			Digger.Status s = digger.tick(me, ctx.controls());
			if (s == Digger.Status.FAILED) {
				return fail("não consegui abrir em cima: " + digger.failure());
			}
			if (s == Digger.Status.DONE) {
				digger = null;
			}
			return Status.RUNNING;
		}
		BlockPos above = me.blockPosition().above(2);
		if (me.onGround() && !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
			if (level.getBlockState(above).getDestroySpeed(level, above) < 0) {
				return fail("tem um bloco que não quebra em cima de mim");
			}
			digger = new Digger(level, above, false); // o teto do abrigo
			return Status.RUNNING;
		}
		int slot = ShelterSkill.filler(me.getInventory());
		if (slot < 0) {
			return fail("fiquei sem bloco para subir do buraco");
		}
		if (me.onGround()) {
			pillarY = me.getBlockY();
			ctx.controls().jump(true);
			ctx.controls().lookAt(me.position().add(0, -1, 0));
		} else if (pillarY != Integer.MIN_VALUE && me.getY() >= pillarY + 1.05) {
			Stacks.hold(me.getInventory(), slot);
			Item item = me.getMainHandItem().getItem();
			Placer.place(me, item, new BlockPos(me.getBlockX(), pillarY, me.getBlockZ()));
			pillarY = Integer.MIN_VALUE;
		} else {
			ctx.controls().lookAt(me.position().add(0, -1, 0));
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

	/** Uma habilidade depois da outra (aqui: sair do buraco e então fazer o que pediram). */
	public static final class Then implements Skill {
		private final Skill first;
		private final Skill then;
		private boolean firstDone;

		public Then(Skill first, Skill then) {
			this.first = first;
			this.then = then;
		}

		@Override
		public String name() {
			return then.name();
		}

		@Override
		public String describe() {
			return firstDone ? then.describe() : first.describe();
		}

		@Override
		public Status tick(SkillContext ctx) {
			if (!firstDone) {
				Status s = first.tick(ctx);
				if (s == Status.FAILED) {
					return Status.FAILED;
				}
				firstDone = s == Status.SUCCEEDED;
				return Status.RUNNING;
			}
			return then.tick(ctx);
		}

		@Override
		public String failureReason() {
			return firstDone ? then.failureReason() : first.failureReason();
		}

		@Override
		public boolean patient() {
			return firstDone && then.patient();
		}
	}
}

package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.hands.Food;
import dev.vry.waifuscam.hands.Stacks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Comer o melhor que tiver: segura o botão direito até terminar, como um player. */
public final class EatSkill implements Skill {
	private static final int TIMEOUT_TICKS = 20 * 6;

	private final boolean evenIfFull;
	private int ticks;
	private int startCount = -1;
	private int slot = -1;
	private boolean started;
	private String failure;

	public EatSkill(boolean evenIfFull) {
		this.evenIfFull = evenIfFull;
	}

	@Override
	public String name() {
		return "eat";
	}

	@Override
	public String describe() {
		return "comendo";
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer p = ctx.player();
		if (!started) {
			if (!p.getFoodData().needsFood() && !evenIfFull) {
				return fail("não estou com fome");
			}
			slot = Food.best(p);
			if (slot < 0) {
				return fail("não tenho nada para comer");
			}
			Stacks.hold(p.getInventory(), slot);
			ItemStack food = p.getMainHandItem();
			startCount = food.getCount();
			p.gameMode.useItem(p, p.level(), food, InteractionHand.MAIN_HAND);
			started = true;
			return Status.RUNNING;
		}
		if (++ticks > TIMEOUT_TICKS) {
			p.releaseUsingItem();
			return fail("não consegui comer");
		}
		if (p.isUsingItem()) {
			return Status.RUNNING;
		}
		// Terminou de usar: se a pilha diminuiu (ou virou tigela), comeu.
		ItemStack hand = p.getMainHandItem();
		if (hand.isEmpty() || hand.getCount() < startCount || !Food.edible(hand)) {
			return Status.SUCCEEDED;
		}
		return fail("não consegui comer");
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

package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Entregar itens para alguém: se não tiver, consegue primeiro (craftando ou
 * minerando); depois vai até a pessoa e joga na frente dela.
 */
public final class GiveSkill implements Skill {
	private final UUID targetId;
	private final String targetName;
	private final Choice items;
	private final int count;
	private final String label;

	private Skill child;
	private boolean obtained;
	private String failure;

	public GiveSkill(ServerPlayer target, Choice items, int count, String label) {
		this.targetId = target.getUUID();
		this.targetName = target.getGameProfile().name();
		this.items = items;
		this.count = Math.max(1, count);
		this.label = label;
	}

	@Override
	public String name() {
		return "give";
	}

	@Override
	public String describe() {
		return child != null && !obtained ? child.describe() + " para " + targetName : "levando " + label + " para " + targetName;
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer target = ctx.server().getPlayerList().getPlayer(targetId);
		if (target == null) {
			return fail(targetName + " saiu do jogo");
		}
		Inventory inv = ctx.player().getInventory();
		if (!obtained) {
			if (Stacks.count(inv, items.items()) >= count) {
				obtained = true;
				child = null;
			} else {
				if (child == null) {
					child = new ObtainSkill(items, count, label);
				}
				Status s = child.tick(ctx);
				if (s == Status.FAILED) {
					return fail(child.failureReason());
				}
				return Status.RUNNING;
			}
		}
		if (target.level() != ctx.level()) {
			return fail(targetName + " está em outra dimensão");
		}
		if (ctx.player().distanceTo(target) > 2.5) {
			if (child == null || ctx.server().getTickCount() % 40 == 0) {
				child = new GotoSkill(LevelTerrain.of(target.blockPosition()), 2.0, targetName);
			}
			if (child.tick(ctx) == Status.FAILED) {
				return fail("não consigo chegar até " + targetName);
			}
			return Status.RUNNING;
		}
		// Do lado dele: olha e joga os itens.
		ctx.player().lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		int left = count;
		for (int i = 0; i < Stacks.MAIN_SLOTS && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && items.accepts(Stacks.id(s))) {
				ItemStack thrown = s.split(Math.min(left, s.getCount()));
				left -= thrown.getCount();
				ctx.player().drop(thrown, false, Prediction.SERVER_ONLY);
			}
		}
		inv.setChanged();
		return left == 0 ? Status.SUCCEEDED : fail("só tinha " + (count - left));
	}

	private Status fail(String reason) {
		failure = reason;
		return Status.FAILED;
	}

	@Override
	public boolean patient() {
		return child != null && child.patient();
	}

	@Override
	public String failureReason() {
		return failure;
	}
}

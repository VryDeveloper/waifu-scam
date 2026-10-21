package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.control.Human;
import dev.vry.waifuscam.craft.Knowledge.Choice;
import dev.vry.waifuscam.hands.BlockFinder;
import dev.vry.waifuscam.hands.Combat;
import dev.vry.waifuscam.hands.Digger;
import dev.vry.waifuscam.hands.Placer;
import dev.vry.waifuscam.hands.Stacks;
import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Habilidades pequenas de mão: colocar bloco, jogar item no chão, pegar o que
 * está no chão, clicar num bloco e quebrar um bloco específico.
 */
public final class HandSkills {
	private HandSkills() {}

	/** O bicho que um player está atacando ou olhando (para "me ajuda a bater nele"). */
	public static Optional<LivingEntity> helpTarget(ServerPlayer helper, ServerPlayer friend) {
		LivingEntity hurt = friend.getLastHurtMob();
		if (hurt != null && hurt.isAlive() && hurt != helper && !(hurt instanceof Player)
				&& friend.tickCount - friend.getLastHurtMobTimestamp() < 20 * 15 && hurt.distanceTo(friend) < 24) {
			return Optional.of(hurt);
		}
		Vec3 eye = friend.getEyePosition();
		Vec3 look = friend.getLookAngle();
		List<LivingEntity> near = friend.level().getEntitiesOfClass(LivingEntity.class, friend.getBoundingBox().inflate(16),
				e -> e.isAlive() && e != helper && e != friend && !(e instanceof Player));
		Optional<LivingEntity> looked = near.stream()
				.filter(e -> {
					Vec3 to = e.getBoundingBox().getCenter().subtract(eye).normalize();
					return to.dot(look) > Math.cos(Math.toRadians(14)) && friend.hasLineOfSight(e);
				})
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(friend)));
		if (looked.isPresent()) {
			return looked;
		}
		return near.stream().filter(e -> Combat.isHostile(e, friend) || Combat.isHostile(e, helper))
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(friend)));
	}

	// ---- colocar ----------------------------------------------------------------

	public static final class Place implements Skill {
		private final Choice item;
		private final String label;
		private int wait = Human.reaction();
		private String failure;

		public Place(Choice item, String label) {
			this.item = item;
			this.label = label;
		}

		@Override
		public String name() {
			return "place";
		}

		@Override
		public String describe() {
			return "colocando " + label;
		}

		@Override
		public Status tick(SkillContext ctx) {
			ServerPlayer me = ctx.player();
			int slot = Stacks.find(me.getInventory(), s -> item.accepts(Stacks.id(s)));
			if (slot < 0) {
				return fail("não tenho " + label);
			}
			if (!(me.getInventory().getItem(slot).getItem() instanceof BlockItem)) {
				return fail(label + " não é um bloco; posso jogar no chão (drop)");
			}
			ctx.controls().lookAt(me.position().add(me.getLookAngle().multiply(1, 0, 1).normalize().scale(1.5)));
			if (wait-- > 0) {
				return Status.RUNNING;
			}
			BlockPos at = Placer.placeNear(me, me.getInventory().getItem(slot).getItem());
			return at != null ? Status.SUCCEEDED : fail("não achei lugar para colocar aqui");
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

	// ---- jogar no chão --------------------------------------------------------------

	public static final class Drop implements Skill {
		private final Choice item;
		private final int count;
		private final String label;
		private int wait = Human.reaction();
		private String failure;

		public Drop(Choice item, int count, String label) {
			this.item = item;
			this.count = count;
			this.label = label;
		}

		@Override
		public String name() {
			return "drop";
		}

		@Override
		public String describe() {
			return "jogando " + label + " no chão";
		}

		@Override
		public Status tick(SkillContext ctx) {
			ServerPlayer me = ctx.player();
			Inventory inv = me.getInventory();
			if (Stacks.count(inv, item.items()) == 0) {
				return fail("não tenho " + label);
			}
			// Olha um pouco para baixo, na frente dela, e joga.
			Vec3 front = me.getEyePosition().add(me.getLookAngle().multiply(1, 0, 1).normalize().scale(2)).add(0, -1.4, 0);
			ctx.controls().lookAt(front);
			if (wait-- > 0) {
				return Status.RUNNING;
			}
			int left = count;
			for (int i = 0; i < Stacks.MAIN_SLOTS && left > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty() && item.accepts(Stacks.id(s))) {
					ItemStack thrown = s.split(Math.min(left, s.getCount()));
					left -= thrown.getCount();
					me.drop(thrown, false, Prediction.SERVER_ONLY);
				}
			}
			inv.setChanged();
			me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			return Status.SUCCEEDED;
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

	// ---- pegar do chão ----------------------------------------------------------------

	public static final class Pickup implements Skill {
		private final Choice item; // null = qualquer coisa
		private final String label;
		private Skill walk;
		private int ticks;
		private boolean gotSomething;
		private String failure;

		public Pickup(Choice item, String label) {
			this.item = item;
			this.label = label;
		}

		@Override
		public String name() {
			return "pickup";
		}

		@Override
		public String describe() {
			return "pegando " + label + " do chão";
		}

		@Override
		public Status tick(SkillContext ctx) {
			ServerPlayer me = ctx.player();
			if (++ticks > 20 * 40) {
				return gotSomething ? Status.SUCCEEDED : fail("não consegui pegar");
			}
			Optional<ItemEntity> next = me.level().getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(12, 4, 12),
							e -> e.isAlive() && !e.getItem().isEmpty() && (item == null || item.accepts(Stacks.id(e.getItem()))))
					.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
			if (next.isEmpty()) {
				return gotSomething || ticks > 10 ? (gotSomething ? Status.SUCCEEDED : fail("não tem " + label + " no chão por perto"))
						: Status.RUNNING;
			}
			gotSomething = true;
			if (walk == null || ticks % 20 == 0) {
				walk = new GotoSkill(LevelTerrain.of(next.get().blockPosition()), 0.6, "item");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				return fail("não consigo chegar no item");
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

	// ---- clicar num bloco -----------------------------------------------------------

	public static final class Use implements Skill {
		private final Set<Block> blocks;
		private final String label;
		private BlockPos target;
		private Skill walk;
		private int wait = Human.reaction();
		private String failure;

		public Use(Set<Block> blocks, String label) {
			this.blocks = blocks;
			this.label = label;
		}

		@Override
		public String name() {
			return "use";
		}

		@Override
		public String describe() {
			return "usando " + label;
		}

		@Override
		public Status tick(SkillContext ctx) {
			ServerPlayer me = ctx.player();
			ServerLevel level = ctx.level();
			if (target == null) {
				List<BlockPos> found = BlockFinder.find(level, me.blockPosition(), 16, 1,
						p -> blocks.contains(level.getBlockState(p).getBlock()));
				if (found.isEmpty()) {
					return fail("não achei " + label + " por perto");
				}
				target = found.get(0);
			}
			if (!me.isWithinBlockInteractionRange(target, 0)) {
				if (walk == null) {
					walk = new GotoSkill(LevelTerrain.of(target), 2.5, label);
				}
				return walk.tick(ctx) == Status.FAILED ? fail("não consigo chegar em " + label) : Status.RUNNING;
			}
			Vec3 center = Vec3.atCenterOf(target);
			ctx.controls().lookAt(center);
			if (wait-- > 0 || !Controls.aimed(me, center, 20)) {
				return Status.RUNNING;
			}
			// Mão vazia, para não colocar um bloco sem querer.
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				if (me.getInventory().getItem(i).isEmpty()) {
					me.getInventory().setSelectedSlot(i);
					break;
				}
			}
			Direction face = Direction.getApproximateNearest(me.getEyePosition().subtract(center));
			Vec3 hit = center.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			me.gameMode.useItemOn(me, level, me.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, target, false));
			me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			return Status.SUCCEEDED;
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

	// ---- quebrar um bloco específico -----------------------------------------------------

	public static final class Mine implements Skill {
		private final Set<Block> blocks;
		private final int count;
		private final String label;
		private final Set<BlockPos> unreachable = new HashSet<>();
		private BlockPos target;
		private Skill walk;
		private Digger digger;
		private int broken;
		private int pause;
		private int pickup = -1;
		private String failure;

		public Mine(Set<Block> blocks, int count, String label) {
			this.blocks = blocks;
			this.count = Math.max(1, count);
			this.label = label;
		}

		@Override
		public String name() {
			return "mine";
		}

		@Override
		public String describe() {
			return "quebrando " + label;
		}

		@Override
		public Status tick(SkillContext ctx) {
			ServerPlayer me = ctx.player();
			ServerLevel level = ctx.level();
			if (pickup >= 0) {
				// Pega o que caiu antes de seguir.
				Optional<ItemEntity> drop = level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(3), e -> e.isAlive())
						.stream().findFirst();
				if (drop.isEmpty() || ++pickup > 20 * 5) {
					pickup = -1;
					walk = null;
					target = null;
					pause = Human.pause();
					return broken >= count ? Status.SUCCEEDED : Status.RUNNING;
				}
				if (walk == null || pickup % 20 == 0) {
					walk = new GotoSkill(LevelTerrain.of(drop.get().blockPosition()), 0.6, "item");
				}
				walk.tick(ctx);
				return Status.RUNNING;
			}
			if (pause-- > 0) {
				return Status.RUNNING;
			}
			if (digger != null) {
				Digger.Status s = digger.tick(me, ctx.controls());
				if (s == Digger.Status.DONE) {
					digger = null;
					broken++;
					pickup = 0;
				} else if (s == Digger.Status.FAILED) {
					unreachable.add(digger.pos());
					String why = digger.failure();
					digger = null;
					target = null;
					if (why != null && why.contains("ferramenta")) {
						return fail("não tenho ferramenta para quebrar " + label);
					}
				}
				return Status.RUNNING;
			}
			if (target == null) {
				List<BlockPos> found = BlockFinder.find(level, me.blockPosition(), 16, 4,
						p -> blocks.contains(level.getBlockState(p).getBlock()) && !unreachable.contains(p.immutable())
								&& BlockFinder.exposed(level, p) && !p.equals(me.blockPosition().below()));
				if (found.isEmpty()) {
					return broken > 0 ? Status.SUCCEEDED : fail("não achei " + label + " por perto");
				}
				target = found.get(0);
				walk = null;
			}
			if (me.isWithinBlockInteractionRange(target, 0)) {
				digger = new Digger(level, target);
				return Status.RUNNING;
			}
			if (walk == null) {
				walk = new GotoSkill(LevelTerrain.of(target), 3.0, label);
			}
			if (walk.tick(ctx) == Status.FAILED) {
				unreachable.add(target);
				target = null;
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

	/** Descer do barco, carrinho ou montaria. */
	public static final class Dismount implements Skill {
		@Override
		public String name() {
			return "dismount";
		}

		@Override
		public String describe() {
			return "descendo";
		}

		@Override
		public Status tick(SkillContext ctx) {
			if (ctx.player().isPassenger()) {
				ctx.player().stopRiding();
			}
			return Status.SUCCEEDED;
		}

		@Override
		public String failureReason() {
			return null;
		}
	}
}

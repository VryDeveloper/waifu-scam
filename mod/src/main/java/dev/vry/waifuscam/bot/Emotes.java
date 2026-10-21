package dev.vry.waifuscam.bot;

import dev.vry.waifuscam.control.Controls;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Expressões do corpo dela, feitas só com o que o jogo já tem (todo mundo vê,
 * sem mod no cliente): corações, acenar, pular de alegria, sim e não com a
 * cabeça, ficar tímida, girar, agachar e sentar (montada num assento invisível).
 *
 * Rodam por cima do que ela estiver fazendo, por um ou dois segundos. Sentar é
 * a exceção: ela fica sentada até precisar levantar.
 */
public final class Emotes {
	public static final Set<String> NAMES = Set.of("hearts", "wave", "jump", "nod", "shake", "shy", "spin", "crouch",
			"sit", "stand", "happy", "music");
	/** Nomes em português que a IA pode usar. */
	private static final Map<String, String> PT = Map.ofEntries(
			Map.entry("coracoes", "hearts"), Map.entry("corações", "hearts"), Map.entry("coracao", "hearts"),
			Map.entry("amor", "hearts"), Map.entry("acenar", "wave"), Map.entry("tchau", "wave"), Map.entry("oi", "wave"),
			Map.entry("pular", "jump"), Map.entry("pulinho", "jump"), Map.entry("sim", "nod"), Map.entry("concordar", "nod"),
			Map.entry("nao", "shake"), Map.entry("não", "shake"), Map.entry("timida", "shy"), Map.entry("tímida", "shy"),
			Map.entry("vergonha", "shy"), Map.entry("girar", "spin"), Map.entry("agachar", "crouch"),
			Map.entry("sentar", "sit"), Map.entry("levantar", "stand"), Map.entry("feliz", "happy"),
			Map.entry("alegria", "happy"), Map.entry("cantar", "music"), Map.entry("musica", "music"));
	private static final String SEAT_TAG = "waifuscam_seat";

	private String current;
	private int ticks;
	private Entity lookTarget;
	private Display.BlockDisplay seat;
	private int seatedTicks;

	/** "corações" → "hearts"; null se não conhece. */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		s = PT.getOrDefault(s, s);
		return NAMES.contains(s) ? s : null;
	}

	public boolean sitting() {
		return seat != null;
	}

	/** Começa uma expressão. target = para quem (acenar, olhar), pode ser null. */
	public void play(ServerPlayer me, String name, Entity target) {
		String e = normalize(name);
		if (e == null) {
			return;
		}
		lookTarget = target;
		switch (e) {
			case "sit" -> sit(me);
			case "stand" -> standUp(me);
			default -> {
				current = e;
				ticks = switch (e) {
					case "spin" -> 30;
					case "hearts", "happy", "music" -> 40;
					default -> 36;
				};
			}
		}
	}

	/** Um tick: aplica a expressão por cima dos controles. */
	public void tick(ServerPlayer me, Controls controls) {
		if (seat != null) {
			seatedTicks++;
			if (!seat.isAlive() || !me.isPassenger() || seatedTicks > 20 * 120) {
				standUp(me);
			} else if (lookTarget != null && lookTarget.isAlive()) {
				controls.lookAt(lookTarget instanceof net.minecraft.world.entity.LivingEntity l ? l.getEyePosition() : lookTarget.position());
			}
		}
		if (current == null) {
			return;
		}
		ServerLevel level = me.level();
		int t = ticks--;
		Vec3 eye = me.getEyePosition();
		Vec3 front = lookTarget != null && lookTarget.isAlive()
				? lookTarget.getEyePosition()
				: eye.add(Vec3.directionFromRotation(0, me.getYHeadRot()).scale(4));
		switch (current) {
			case "hearts" -> {
				if (t % 8 == 0) {
					particles(level, me, ParticleTypes.HEART, 2);
				}
				controls.lookAt(front);
			}
			case "happy" -> {
				if (t % 8 == 0) {
					particles(level, me, ParticleTypes.HAPPY_VILLAGER, 4);
				}
				controls.jump(me.onGround() && t % 14 == 0);
				controls.lookAt(front);
			}
			case "music" -> {
				if (t % 10 == 0) {
					particles(level, me, ParticleTypes.NOTE, 1);
				}
				controls.lookAt(front.add(0, Math.sin(t / 3.0) * 0.6, 0));
			}
			case "wave" -> {
				controls.lookAt(front);
				if (t % 6 == 0) {
					me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				}
			}
			case "jump" -> {
				controls.jump(me.onGround() && t % 12 == 0);
				controls.lookAt(front);
			}
			case "nod" -> controls.lookAt(front.add(0, (t / 6) % 2 == 0 ? -1.2 : 0.4, 0));
			case "shake" -> {
				Vec3 side = Vec3.directionFromRotation(0, me.getYHeadRot() + 90).scale((t / 5) % 2 == 0 ? 1.2 : -1.2);
				controls.lookAt(front.add(side));
			}
			case "shy" -> {
				controls.lookAt(eye.add(Vec3.directionFromRotation(55, me.getYHeadRot() + 20).scale(3)));
				if (t % 10 == 0) {
					particles(level, me, ParticleTypes.HEART, 1);
				}
			}
			case "spin" -> controls.lookAt(eye.add(Vec3.directionFromRotation(0, me.getYHeadRot() + 25).scale(4)));
			case "crouch" -> {
				controls.sneak((t / 6) % 2 == 0);
				controls.lookAt(front);
			}
			default -> {}
		}
		if (ticks <= 0) {
			current = null;
		}
	}

	private static void particles(ServerLevel level, ServerPlayer me, ParticleOptions type, int n) {
		level.sendParticles(type, me.getX(), me.getY() + 2.1, me.getZ(), n, 0.35, 0.2, 0.35, 0.02);
	}

	// ---- sentar --------------------------------------------------------------------

	/** Senta no chão onde está: monta num bloco de exibição vazio, invisível. */
	private void sit(ServerPlayer me) {
		if (seat != null || !me.onGround() || me.isPassenger()) {
			return;
		}
		ServerLevel level = me.level();
		Display.BlockDisplay s = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (s == null) {
			return;
		}
		s.snapTo(me.getX(), me.getY() + 0.1, me.getZ(), me.getYRot(), 0);
		s.setNoGravity(true);
		s.addTag(SEAT_TAG);
		level.addFreshEntity(s);
		if (me.startRiding(s, true, false)) {
			seat = s;
			seatedTicks = 0;
		} else {
			s.discard();
		}
	}

	/** Levanta (antes de andar, lutar, fugir...). */
	public void standUp(ServerPlayer me) {
		if (seat == null) {
			return;
		}
		if (me != null && me.isPassenger()) {
			me.stopRiding();
		}
		seat.discard();
		seat = null;
	}

	/** Assentos que sobraram de uma sessão anterior (jogo fechado com ela sentada). */
	public static void cleanup(ServerLevel level, ServerPlayer near) {
		for (Entity e : level.getEntities((Entity) null, near.getBoundingBox().inflate(64), e -> e.entityTags().contains(SEAT_TAG))) {
			if (e.getPassengers().isEmpty()) {
				e.discard();
			}
		}
	}
}

package dev.vry.waifuscam.bot;

import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.skill.Skill;
import dev.vry.waifuscam.skill.SkillContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Cuida do ciclo de vida da personagem (entrar, sair, morrer, renascer) e roda
 * a habilidade atual dela, uma de cada vez.
 *
 * Guarda a personagem pelo UUID, não pela instância: a cada respawn o jogo
 * cria um ServerPlayer novo.
 */
public final class CompanionManager {
	/** Ticks entre morrer e apertar "Respawn" (2 s). */
	private static final int RESPAWN_DELAY_TICKS = 40;

	/** Distância para ela olhar para alguém quando está parada. */
	private static final double LOOK_AROUND_RANGE = 8;

	/**
	 * Avisa a Mente quando uma habilidade termina. reason == null quando deu certo;
	 * cancelled == true quando não foi falha da habilidade: foi trocada, mandaram
	 * parar, ela morreu ou saiu do mundo.
	 */
	@FunctionalInterface
	public interface SkillListener {
		void onSkillEnded(String id, String skill, String reason, boolean cancelled);
	}

	private final MinecraftServer server;
	private final BiConsumer<String, String> events; // (evento, detalhe) → Mente
	private final SkillListener skillListener;
	private final Controls controls = new Controls();
	private final Reflexes reflexes;

	private Skill skill;
	private String skillId;

	private String username;
	private SkinData skin;
	private UUID uuid;
	private int deadTicks;

	public CompanionManager(MinecraftServer server, BiConsumer<String, String> events, SkillListener skillListener) {
		this.server = server;
		this.events = events;
		this.skillListener = skillListener;
		this.reflexes = new Reflexes(events);
	}

	/** Liga/desliga os reflexos (fugir, revidar, comer sozinha). */
	public void setReflexes(boolean enabled) {
		reflexes.setEnabled(enabled);
	}

	/** O que o reflexo está fazendo agora (comendo...), ou null. */
	public String reflexDoing() {
		return reflexes.doing();
	}

	public Controls controls() {
		return controls;
	}

	/** Chamado quando a Mente diz qual personagem é (welcome). */
	public void configure(String username, SkinData skin) {
		this.username = username;
		this.skin = skin;
	}

	public boolean isConfigured() {
		return username != null;
	}

	public String username() {
		return username;
	}

	public CompanionPlayer player() {
		if (uuid == null) {
			return null;
		}
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		return p instanceof CompanionPlayer c ? c : null;
	}

	public boolean isOnline() {
		return player() != null;
	}

	/**
	 * Coloca a personagem no mundo perto de near (ou no spawn do mundo, se null).
	 * Devolve uma mensagem de erro, ou null se deu certo. Precisa rodar na thread do servidor.
	 */
	public String spawn(ServerPlayer near) {
		if (!isConfigured()) {
			return "a Mente ainda não mandou a personagem (ela está rodando?)";
		}
		if (isOnline()) {
			return username + " já está no mundo";
		}
		if (server.getPlayerList().getPlayerByName(username) != null) {
			return "já existe um player chamado " + username;
		}

		ServerLevel level;
		Vec3 pos;
		float yaw;
		if (near != null) {
			level = near.level();
			Vec3 look = Vec3.directionFromRotation(0, near.getYRot()).scale(2);
			pos = near.position().add(look.x, 0, look.z); // dois blocos à frente de quem chamou
			yaw = near.getYRot() + 180; // olhando para ele
		} else {
			level = server.overworld();
			pos = Vec3.atBottomCenterOf(level.getLevelData().getRespawnData().pos());
			yaw = 0;
		}

		CompanionPlayer player = CompanionPlayer.spawn(server, level, CompanionPlayer.profile(username, skin), pos, yaw);
		uuid = player.getUUID();
		events.accept("spawned", null);
		return null;
	}

	/** Tira a personagem do mundo, como um player saindo (o inventário fica salvo). */
	public boolean despawn(String reason) {
		CompanionPlayer player = player();
		if (player == null) {
			return false;
		}
		endSkill("saí do mundo", true);
		player.connection.disconnect(Component.literal(reason));
		events.accept("despawned", reason);
		return true;
	}

	// ---- habilidades ----

	/** Começa uma habilidade; a que estava rodando é interrompida (e reportada como tal). */
	public void startSkill(String id, Skill next) {
		endSkill("interrompida por outra ação", true);
		skill = next;
		skillId = id;
	}

	/** Para o que estiver fazendo. Devolve false se não estava fazendo nada. */
	public boolean stopSkill() {
		if (skill == null) {
			return false;
		}
		endSkill("mandaram parar", true);
		return true;
	}

	public Skill currentSkill() {
		return skill;
	}

	public String currentSkillId() {
		return skillId;
	}

	/** Encerra a habilidade atual. reason == null significa que deu certo. */
	private void endSkill(String reason) {
		endSkill(reason, false);
	}

	private void endSkill(String reason, boolean cancelled) {
		if (skill == null) {
			return;
		}
		Skill ended = skill;
		String id = skillId;
		skill = null;
		skillId = null;
		controls.release();
		skillListener.onSkillEnded(id, ended.name(), reason, cancelled);
	}

	/**
	 * Chamado no fim de cada tick do servidor. As teclas definidas aqui são
	 * aplicadas no corpo no começo do próximo tick (CompanionPlayer.tick).
	 *
	 * O corpo de um player morto sai do mundo depois da animação de morte e
	 * para de receber tick(), mas continua na lista de players até o respawn.
	 * Por isso a contagem do respawn também fica aqui, e não nele.
	 */
	public void tick() {
		controls.release();
		CompanionPlayer player = player();
		if (player == null) {
			deadTicks = 0;
			return;
		}
		if (player.isDeadOrDying()) {
			reflexes.reset();
			endSkill("morri", true);
			if (++deadTicks >= RESPAWN_DELAY_TICKS) {
				deadTicks = 0;
				player.pressRespawn();
			}
			return;
		}
		deadTicks = 0;

		SkillContext ctx = new SkillContext(server, player, controls);
		if (reflexes.before(ctx, server.getTickCount())) {
			reflexes.after(ctx);
			return; // a habilidade fica pausada neste tick
		}
		if (skill != null) {
			Skill.Status status = skill.tick(ctx);
			if (status == Skill.Status.SUCCEEDED) {
				endSkill(null);
			} else if (status == Skill.Status.FAILED) {
				String reason = skill.failureReason();
				endSkill(reason == null ? "não deu certo" : reason);
			}
		} else {
			lookAtNearestPlayer(player);
		}
		reflexes.after(ctx);
	}

	/** Parada, ela olha para o player mais perto, como alguém de verdade faria. */
	private void lookAtNearestPlayer(CompanionPlayer me) {
		ServerPlayer nearest = null;
		double best = LOOK_AROUND_RANGE * LOOK_AROUND_RANGE;
		for (ServerPlayer p : me.level().players()) {
			if (p == me || p.isSpectator()) {
				continue;
			}
			double d = p.distanceToSqr(me);
			if (d < best) {
				best = d;
				nearest = p;
			}
		}
		if (nearest != null) {
			controls.lookAt(nearest.getEyePosition());
		}
	}

	public void onDied(CompanionPlayer player, String deathMessage) {
		events.accept("died", deathMessage);
	}

	public void onRespawned(CompanionPlayer player) {
		events.accept("respawned", null);
	}
}

package dev.vry.waifuscam.bot;

import dev.vry.waifuscam.WaifuScamMod;
import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.skill.EscapeSkill;
import dev.vry.waifuscam.skill.Skill;
import dev.vry.waifuscam.skill.SkillContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
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
	private final Eyes eyes = new Eyes();
	private final Emotes emotes = new Emotes();

	private Skill skill;
	private String skillId;

	private String username;
	private SkinData skin;
	private UUID uuid;
	private int deadTicks;

	// Vigia: habilidade rodando sem ela sair do lugar nem mudar o inventário por muito tempo = travou.
	private static final int WATCHDOG_TICKS = 20 * 60;
	private int watchdogTicks;
	private net.minecraft.world.phys.Vec3 watchdogPos;
	private int watchdogInv;

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

	/** O que ela vê e lembra de ter visto. */
	public Eyes eyes() {
		return eyes;
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
		Emotes.cleanup(level, player);
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
		emotes.standUp(player);
		player.connection.disconnect(Component.literal(reason));
		events.accept("despawned", reason);
		return true;
	}

	// ---- habilidades ----

	/** Começa uma habilidade; a que estava rodando é interrompida (e reportada como tal). */
	public void startSkill(String id, Skill next) {
		endSkill("interrompida por outra ação", true);
		// Pediram algo enquanto ela está num buraco (o abrigo da noite): primeiro ela sai.
		CompanionPlayer me = player();
		Set<String> stayPut = Set.of("shelter", "sleep", "eat", "stop", "escape");
		if (me != null && !stayPut.contains(next.name()) && EscapeSkill.trapped(me)) {
			next = new EscapeSkill.Then(new EscapeSkill(), next);
		}
		// Montada (barco, cavalo...): desce antes de sair andando. Seguir e pescar dá para fazer montada.
		Set<String> mounted = Set.of("mount", "follow", "fish", "stop", "eat", "tame", "dismount");
		if (me != null && me.isPassenger() && !mounted.contains(next.name()) && !emotes.sitting()) {
			me.stopRiding();
		}
		skill = next;
		skillId = id;
		watchdogTicks = 0;
		WaifuScamMod.LOGGER.info("[{}] começou {} ({}): {}", username, next.name(), id, next.describe());
	}

	/** Para o que estiver fazendo. Devolve false se não estava fazendo nada. */
	public boolean stopSkill() {
		if (skill == null) {
			return false;
		}
		endSkill("mandaram parar", true);
		return true;
	}

	/** Para tudo depois de um erro (habilidade e reflexo), avisando a Mente do motivo. */
	public void abortSkill(String reason) {
		reflexes.reset();
		try {
			endSkill(reason);
		} catch (RuntimeException ignored) {
			skill = null;
			skillId = null;
		}
		controls.release();
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
		if (reason == null) {
			WaifuScamMod.LOGGER.info("[{}] terminou {} ({})", username, ended.name(), id);
		} else {
			WaifuScamMod.LOGGER.info("[{}] parou {} ({}): {}", username, ended.name(), id, reason);
		}
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
	private int crouchBack;

	/** Ela agacha de volta (responder o carinho), por alguns instantes, com coraçõezinhos. */
	public void crouchBack(String to) {
		crouchBack = 36;
		emote("hearts", to);
	}

	public boolean sitting() {
		return emotes.sitting();
	}

	/** Uma expressão (corações, acenar, sentar...). to = para quem, ou null. */
	public void emote(String name, String to) {
		CompanionPlayer me = player();
		if (me == null || me.isDeadOrDying()) {
			return;
		}
		ServerPlayer target = to != null ? server.getPlayerList().getPlayerByName(to) : null;
		emotes.play(me, name, target);
	}

	public void tick() {
		controls.release();
		CompanionPlayer player = player();
		if (player == null) {
			deadTicks = 0;
			return;
		}
		if (player.isDeadOrDying()) {
			emotes.standUp(player);
			reflexes.reset();
			endSkill("morri", true);
			if (++deadTicks >= RESPAWN_DELAY_TICKS) {
				deadTicks = 0;
				player.pressRespawn();
			}
			return;
		}
		deadTicks = 0;
		if (server.getTickCount() % 20 == 0) {
			eyes.look(player, server.getTickCount());
		}

		// Sentada: levanta para fazer alguma coisa ou quando aparece perigo.
		if (emotes.sitting() && (skill != null || dev.vry.waifuscam.hands.Combat.nearestHostile(player, 10).isPresent())) {
			emotes.standUp(player);
		}
		SkillContext ctx = new SkillContext(server, player, controls);
		if (reflexes.before(ctx, server.getTickCount())) {
			reflexes.after(ctx);
			return; // a habilidade fica pausada neste tick
		}
		if (skill != null && stuck(player)) {
			endSkill("fiquei travada sem conseguir fazer nada");
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
		if (crouchBack > 0) {
			crouchBack--;
			controls.sneak((crouchBack / 6) % 2 == 0); // agacha, levanta, agacha...
		}
		emotes.tick(player, controls);
		reflexes.after(ctx);
	}

	private boolean stuck(CompanionPlayer me) {
		if (skill.patient()) {
			watchdogTicks = 0;
			return false;
		}
		int inv = me.getInventory().getTimesChanged();
		if (watchdogPos == null || me.position().distanceTo(watchdogPos) > 1.5 || inv != watchdogInv) {
			watchdogPos = me.position();
			watchdogInv = inv;
			watchdogTicks = 0;
			return false;
		}
		return ++watchdogTicks > WATCHDOG_TICKS;
	}

	// Olhar em volta quando está parada e ninguém está perto (em vez de olhar para o nada).
	private net.minecraft.world.phys.Vec3 idleLook;
	private int idleLookTicks;

	private void lookAround(CompanionPlayer me) {
		if (idleLook == null || --idleLookTicks <= 0) {
			var rnd = java.util.concurrent.ThreadLocalRandom.current();
			double yaw = Math.toRadians(me.getYRot() + rnd.nextDouble(-110, 110));
			double dist = rnd.nextDouble(4, 12);
			idleLook = me.getEyePosition().add(-Math.sin(yaw) * dist, rnd.nextDouble(-2.5, 1.5), Math.cos(yaw) * dist);
			idleLookTicks = rnd.nextInt(40, 120);
		}
		controls.lookAt(idleLook);
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
		if (nearest != null && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < 97) {
			controls.lookAt(nearest.getEyePosition());
			idleLook = null;
		} else {
			lookAround(me);
		}
	}

	public void onDied(CompanionPlayer player, String deathMessage) {
		events.accept("died", deathMessage);
	}

	public void onRespawned(CompanionPlayer player) {
		events.accept("respawned", null);
	}
}

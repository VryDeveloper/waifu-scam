package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.pathing.LevelTerrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import java.util.Comparator;
import java.util.Optional;

/**
 * Subir num barco, carrinho ou montaria (cavalo, burro, camelo, porco/strider
 * com sela...). Com `player`, prefere o veículo em que ele está (barco tem dois
 * lugares, camelo também): ela vai de passageira com ele. Fica montada até
 * pedirem para descer (dismount) ou ela precisar andar.
 */
public final class RideSkill implements Skill {
	private static final double RADIUS = 24;
	private static final int TIMEOUT_TICKS = 20 * 40;

	private final String kind; // boat, minecart, horse... ou null = qualquer
	private final String withPlayer;
	private Entity vehicle;
	private Skill walk;
	private int ticks;
	private String failure;

	public RideSkill(String kind, String withPlayer) {
		this.kind = kind == null || kind.isBlank() ? null : kind.toLowerCase();
		this.withPlayer = withPlayer;
	}

	@Override
	public String name() {
		return "mount";
	}

	@Override
	public String describe() {
		return "subindo " + (kind != null ? "no " + kind : "na montaria");
	}

	@Override
	public Status tick(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (me.isPassenger()) {
			return Status.SUCCEEDED;
		}
		if (++ticks > TIMEOUT_TICKS) {
			return fail("não consegui subir");
		}
		if (vehicle == null || !vehicle.isAlive()) {
			vehicle = find(ctx).orElse(null);
			walk = null;
			if (vehicle == null) {
				return fail(kind != null ? "não achei " + kind + " livre por perto" : "não achei barco nem montaria livre por perto");
			}
		}
		if (me.distanceTo(vehicle) > 2.5) {
			if (walk == null || ticks % 20 == 0) {
				walk = new GotoSkill(LevelTerrain.of(vehicle.blockPosition()), 1.5, "montaria");
			}
			if (walk.tick(ctx) == Status.FAILED) {
				return fail("não consigo chegar até lá");
			}
			return Status.RUNNING;
		}
		ctx.controls().lookAt(vehicle.getBoundingBox().getCenter());
		// Como um player: clique com a mão vazia (com comida na mão o cavalo comeria).
		var inv = me.getInventory();
		int empty = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				empty = i;
				break;
			}
		}
		if (empty >= 0) {
			inv.setSelectedSlot(empty);
		}
		me.interactOn(vehicle, InteractionHand.MAIN_HAND, vehicle.getBoundingBox().getCenter().subtract(vehicle.position()));
		if (!me.isPassenger() && free(vehicle)) {
			me.startRiding(vehicle);
		}
		return me.isPassenger() ? Status.SUCCEEDED : Status.RUNNING;
	}

	private Optional<Entity> find(SkillContext ctx) {
		ServerPlayer me = ctx.player();
		if (withPlayer != null) {
			Player p = ctx.server().getPlayerList().getPlayerByName(withPlayer);
			if (p != null && p.getVehicle() != null && free(p.getVehicle()) && matches(p.getVehicle())) {
				return Optional.of(p.getVehicle()); // de carona com ele
			}
		}
		return me.level().getEntities(me, me.getBoundingBox().inflate(RADIUS), e -> rideable(e) && free(e) && matches(e))
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
	}

	static boolean rideable(Entity e) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		if (e instanceof AbstractBoat || id.equals("minecart")) {
			return true; // carrinho vazio comum (não o de baú/fornalha/TNT)
		}
		if (e instanceof AbstractHorse horse) {
			return horse.isTamed() && !horse.isBaby(); // cavalo selvagem joga ela no chão
		}
		return (id.equals("pig") || id.equals("strider")) && e instanceof net.minecraft.world.entity.Mob m
				&& m.isSaddled();
	}

	private static boolean free(Entity e) {
		int seats = e instanceof AbstractBoat ? 2 : (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().startsWith("camel")) ? 2 : 1;
		return e.getPassengers().size() < seats;
	}

	private boolean matches(Entity e) {
		if (kind == null) {
			return true;
		}
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return switch (kind) {
			case "boat", "barco" -> e instanceof AbstractBoat;
			case "minecart", "carrinho", "cart" -> e instanceof AbstractMinecart;
			case "horse", "cavalo", "montaria" -> e instanceof AbstractHorse;
			default -> id.contains(kind);
		};
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

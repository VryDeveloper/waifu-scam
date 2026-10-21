package dev.vry.waifuscam.control;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Conduzir a montaria ou o barco em que ela está, na direção de um ponto. O
 * servidor não simula sozinho o veículo de um player, então ela move o veículo
 * direto (com colisão e degrau, como o jogo faz), um pouco por tick.
 */
public final class Driver {
	private Driver() {}

	/** Ela é quem conduz este veículo? */
	public static boolean drives(ServerPlayer me, Entity vehicle) {
		return vehicle.getControllingPassenger() == me || vehicle.getFirstPassenger() == me
				&& (vehicle instanceof AbstractHorse || vehicle instanceof AbstractBoat);
	}

	/** Um tick andando para target. Devolve false quando já chegou (a menos de `stop` blocos). */
	public static boolean drive(ServerPlayer me, Entity vehicle, Vec3 target, double stop) {
		Vec3 d = target.subtract(vehicle.position());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		if (horizontal < stop) {
			return false;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		vehicle.setYRot(yaw);
		if (vehicle instanceof LivingEntity l) {
			l.setYBodyRot(yaw);
			l.setYHeadRot(yaw);
		}
		double speed = vehicle instanceof AbstractBoat ? (vehicle.isInWater() || onWater(vehicle) ? 0.32 : 0.06)
				: vehicle instanceof AbstractHorse ? 0.30 : 0.18;
		double step = Math.min(speed, horizontal - stop + 0.2);
		Vec3 dir = new Vec3(d.x / horizontal, 0, d.z / horizontal).scale(step);
		double vy = vehicle.onGround() || vehicle.isInWater() || onWater(vehicle) ? 0 : -0.35;
		vehicle.move(MoverType.SELF, new Vec3(dir.x, vy, dir.z));
		me.setYRot(yaw);
		return true;
	}

	private static boolean onWater(Entity e) {
		return e.level().getFluidState(e.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER)
				|| e.level().getFluidState(e.blockPosition()).is(net.minecraft.tags.FluidTags.WATER);
	}
}

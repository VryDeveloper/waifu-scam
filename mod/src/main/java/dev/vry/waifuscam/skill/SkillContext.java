package dev.vry.waifuscam.skill;

import dev.vry.waifuscam.bot.CompanionPlayer;
import dev.vry.waifuscam.control.Controls;
import dev.vry.waifuscam.pathing.LevelTerrain;
import dev.vry.waifuscam.pathing.Pos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** O que uma habilidade recebe a cada tick. */
public record SkillContext(MinecraftServer server, CompanionPlayer player, Controls controls) {
	public ServerLevel level() {
		return player.level();
	}

	/**
	 * Bloco dos pés. Em cima de laje ou escada a posição cai dentro do próprio
	 * bloco, então sobe um quando ele não é passável.
	 */
	public Pos feet(LevelTerrain terrain) {
		return feetOf(player, terrain);
	}

	public static Pos feetOf(Entity entity, LevelTerrain terrain) {
		Pos p = LevelTerrain.of(entity.blockPosition());
		return terrain.isPassable(p) ? p : p.above();
	}
}

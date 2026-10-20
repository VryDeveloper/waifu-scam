package dev.vry.waifuscam.pathing;

/**
 * O que o pathfinding precisa saber sobre cada bloco. A implementação de verdade
 * (LevelTerrain) lê o mundo; os testes usam um mapa em memória.
 */
public interface Terrain {
	/** O chunk está carregado? Fora disso o caminho não é planejado. */
	boolean isLoaded(Pos pos);

	/** Dá para ficar de pé em cima deste bloco (tem colisão no topo). */
	boolean isSolid(Pos pos);

	/** O corpo cabe neste bloco (sem colisão). Água conta como passável. */
	boolean isPassable(Pos pos);

	/** Água: dá para nadar, mas custa mais. */
	boolean isWater(Pos pos);

	/** Lava, fogo, cacto, magma, frutinhas... nunca pisar ou atravessar. */
	boolean isDangerous(Pos pos);
}

package dev.vry.waifuscam.control;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tempos de gente: ninguém quebra o próximo bloco no mesmo tick em que o outro
 * caiu. Pequenas pausas aleatórias deixam ela com cara de player, não de macro.
 */
public final class Human {
	private Human() {}

	private static int between(int min, int max) {
		return ThreadLocalRandom.current().nextInt(min, max + 1);
	}

	/** Reação antes de começar algo (mirar, clicar): 0,2 a 0,45 s. */
	public static int reaction() {
		return between(4, 9);
	}

	/** Pausa entre uma tarefa e a próxima (olhar em volta, decidir): 0,4 a 1 s. */
	public static int pause() {
		return between(8, 20);
	}

	/** Tempo para mexer na bancada ou no inventário por item craftado: 0,6 a 1,1 s. */
	public static int craft() {
		return between(12, 22);
	}
}

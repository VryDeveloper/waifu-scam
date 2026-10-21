package dev.vry.waifuscam.skill;

/**
 * Uma ação de médio nível que roda por vários ticks até terminar ou falhar
 * ("ir até o Vry", "seguir o Vry"). A Mente escolhe a habilidade; o código
 * aqui decide como executá-la, tick a tick.
 */
public interface Skill {
	enum Status { RUNNING, SUCCEEDED, FAILED }

	/** Nome no protocolo e no catálogo (ex.: "follow"). */
	String name();

	/** Texto curto do que ela está fazendo, para a Mente e para /waifu status. */
	String describe();

	/** Chamado uma vez por tick do servidor, depois de Controls.release(). */
	Status tick(SkillContext ctx);

	/** Motivo da falha, em português, quando tick() devolveu FAILED. */
	String failureReason();

	/**
	 * true quando ficar parada faz parte do trabalho (esperar a fornalha, dormir,
	 * seguir alguém que parou). O vigia de "travou" não mexe nessas.
	 */
	default boolean patient() {
		return false;
	}
}

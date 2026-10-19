package dev.vry.waifuscam.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração técnica do Corpo, em config/waifuscam.json.
 *
 * O que define a personagem (nome, skin, persona, permissões) fica no pacote de
 * personagem da Mente; aqui só fica o que é do mod.
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String FILE_NAME = "waifuscam.json";

	@SerializedName("mind_url")
	public String mindUrl = "ws://127.0.0.1:8765";

	@SerializedName("reconnect_seconds")
	public int reconnectSeconds = 5;

	/** Lê o arquivo; se não existir ou estiver quebrado, usa os padrões e grava um arquivo novo. */
	public static ModConfig load(Path configDir) {
		Path file = configDir.resolve(FILE_NAME);
		ModConfig config = null;
		if (Files.exists(file)) {
			try {
				config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ModConfig.class);
			} catch (IOException | JsonParseException e) {
				config = null;
			}
		}
		if (config == null) {
			config = new ModConfig();
		}
		config.sanitize();
		config.save(file);
		return config;
	}

	private void sanitize() {
		if (mindUrl == null || mindUrl.isBlank()) {
			mindUrl = "ws://127.0.0.1:8765";
		}
		if (reconnectSeconds < 1) {
			reconnectSeconds = 5;
		}
	}

	private void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// sem permissão de escrita: segue com os valores em memória
		}
	}
}

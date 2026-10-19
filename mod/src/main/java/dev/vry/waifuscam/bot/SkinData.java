package dev.vry.waifuscam.bot;

/** Textura de skin assinada pela Mojang, como vem da Mente no welcome. */
public record SkinData(String value, String signature) {
	public boolean isValid() {
		return value != null && !value.isBlank() && signature != null && !signature.isBlank();
	}
}

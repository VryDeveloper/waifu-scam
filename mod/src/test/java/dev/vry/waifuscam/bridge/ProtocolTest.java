package dev.vry.waifuscam.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

	@Test
	void helloHasEnvelopeAndNullBot() throws Exception {
		JsonObject msg = Protocol.parse(Protocol.hello("fabric", "0.1.0", "26.3"));
		assertEquals("hello", Protocol.type(msg));
		assertEquals(1, msg.get("v").getAsInt());
		assertNull(Protocol.string(msg, "bot"));
		assertEquals("26.3", Protocol.string(msg, "minecraft"));
	}

	@Test
	void pingCarriesBotAndId() throws Exception {
		JsonObject msg = Protocol.parse(Protocol.ping("lumi", "abc", 42L));
		assertEquals("lumi", Protocol.string(msg, "bot"));
		assertEquals("abc", Protocol.string(msg, "id"));
		assertEquals(42L, msg.get("t").getAsLong());
	}

	@Test
	void rejectsOtherVersions() {
		Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
				() -> Protocol.parse("{\"v\":2,\"type\":\"hello\"}"));
		assertTrue(e.getMessage().contains("versão"));
	}

	@Test
	void rejectsGarbage() {
		assertThrows(Protocol.ProtocolException.class, () -> Protocol.parse("not json"));
		assertThrows(Protocol.ProtocolException.class, () -> Protocol.parse("[1,2]"));
		assertThrows(Protocol.ProtocolException.class, () -> Protocol.parse("{\"v\":1}"));
	}
}

package dev.vry.waifuscam.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Permite que a FakeConnection tenha um canal (isConnected() == true). */
@Mixin(Connection.class)
public interface ConnectionAccessor {
	@Accessor("channel")
	void waifuscam$setChannel(Channel channel);
}

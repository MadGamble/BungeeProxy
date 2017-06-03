package net.minelink.bungeeproxy;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.netty.PipelineUtils;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

public class BungeeProxy extends Plugin {

	private final File config = new File("haProxyIps.list");
	private Set<String> haProxyIps = Collections.emptySet();

	@Override
	public void onEnable() {
		try {
			if (config.exists()) {
				haProxyIps = Files.readAllLines(config.toPath()).stream().collect(Collectors.toSet());
			} else {
				Files.write(config.toPath(), Arrays.asList("127.0.0.1"));
			}
			Field remoteAddressField = AbstractChannel.class.getDeclaredField("remoteAddress");
			remoteAddressField.setAccessible(true);
			Field serverChild = PipelineUtils.class.getField("SERVER_CHILD");
			serverChild.setAccessible(true);
			Field modifiersField = Field.class.getDeclaredField("modifiers");
			modifiersField.setAccessible(true);
			modifiersField.setInt(serverChild, serverChild.getModifiers() & ~Modifier.FINAL);
			ChannelInitializer<Channel> bungeeChannelInitializer = PipelineUtils.SERVER_CHILD;
			Method initChannelMethod = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
			initChannelMethod.setAccessible(true);
			serverChild.set(null, new ChannelInitializer<Channel>() {

				@Override
				protected void initChannel(Channel channel) throws Exception {
					boolean haProxy = false;
					if (channel.remoteAddress() instanceof InetSocketAddress) {
						InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
						if (haProxyIps.contains(address.getAddress().getHostAddress())) {
							haProxy = true;
						}
					}
					initChannelMethod.invoke(bungeeChannelInitializer, channel);
					if (haProxy) {
						channel.pipeline().addAfter(PipelineUtils.TIMEOUT_HANDLER, "haproxy-decoder", new HAProxyMessageDecoder());
						channel.pipeline().addAfter("haproxy-decoder", "haproxy-handler", new ChannelInboundHandlerAdapter() {

							@Override
							public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
								if (msg instanceof HAProxyMessage) {
									HAProxyMessage message = (HAProxyMessage) msg;
									remoteAddressField.set(channel, new InetSocketAddress(message.sourceAddress(), message.sourcePort()));
								} else {
									super.channelRead(ctx, msg);
								}
							}
						});
					}
				}
			});
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, e.getMessage(), e);
			getProxy().stop();
		}
	}
}

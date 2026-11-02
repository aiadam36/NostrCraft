package com.nostrcraft.nostr;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * One WebSocket connection to a Nostr relay, built on the Netty that Minecraft already ships
 * (so no extra dependency). Reconnects with exponential backoff until close() is called.
 */
public class RelayConnection {

    public interface Listener {
        void onOpen(RelayConnection c);
        void onMessage(RelayConnection c, String text);
        void onClose(RelayConnection c);
    }

    /** Shared IO threads (daemon, so they never block game shutdown). */
    public static final EventLoopGroup GROUP = new NioEventLoopGroup(2, new DefaultThreadFactory("NostrCraft-IO", true));

    public final String url;
    private final URI uri;
    private final Listener listener;
    private volatile Channel channel;
    private volatile boolean open;
    private volatile boolean wantOpen;
    private volatile int attempt;

    public RelayConnection(String url, Listener listener) {
        this.url = url;
        this.uri = URI.create(url);
        this.listener = listener;
    }

    public boolean isOpen() { return open; }

    public void connect() {
        wantOpen = true;
        doConnect();
    }

    public void close() {
        wantOpen = false;
        Channel c = channel;
        if (c != null) c.close();
    }

    public void send(String text) {
        Channel c = channel;
        if (c != null && open) c.writeAndFlush(new TextWebSocketFrame(text));
    }

    private void doConnect() {
        if (!wantOpen) return;
        try {
            final boolean ssl = "wss".equalsIgnoreCase(uri.getScheme());
            final String host = uri.getHost();
            final int port = uri.getPort() == -1 ? (ssl ? 443 : 80) : uri.getPort();
            final SslContext sslCtx = ssl ? SslContextBuilder.forClient().build() : null;

            Bootstrap b = new Bootstrap()
                    .group(GROUP)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                SslHandler h = sslCtx.newHandler(ch.alloc(), host, port);
                                SSLEngine eng = h.engine();
                                SSLParameters sp = eng.getSSLParameters();
                                sp.setEndpointIdentificationAlgorithm("HTTPS"); // verify the relay's hostname
                                eng.setSSLParameters(sp);
                                p.addLast(h);
                            }
                            p.addLast(new HttpClientCodec(), new HttpObjectAggregator(1 << 16), new Handler());
                        }
                    });
            b.connect(host, port).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) {
                    if (!f.isSuccess()) scheduleReconnect();
                }
            });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!wantOpen) return;
        int a = Math.min(++attempt, 5);
        GROUP.schedule(new Runnable() {
            @Override
            public void run() { doConnect(); }
        }, 1L << a, TimeUnit.SECONDS); // 2s, 4s, ... capped at 32s
    }

    private class Handler extends SimpleChannelInboundHandler<Object> {
        private WebSocketClientHandshaker handshaker;
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders(), 1 << 20);
            handshaker.handshake(ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                channel = ch;
                open = true;
                attempt = 0;
                safe(new Runnable() { public void run() { listener.onOpen(RelayConnection.this); } });
                return;
            }
            if (msg instanceof TextWebSocketFrame) {
                TextWebSocketFrame f = (TextWebSocketFrame) msg;
                if (f.isFinalFragment()) deliver(f.text());
                else { fragments.setLength(0); fragments.append(f.text()); }
            } else if (msg instanceof ContinuationWebSocketFrame) {
                ContinuationWebSocketFrame f = (ContinuationWebSocketFrame) msg;
                fragments.append(f.text());
                if (f.isFinalFragment()) {
                    String s = fragments.toString();
                    fragments.setLength(0);
                    deliver(s);
                }
            } else if (msg instanceof PingWebSocketFrame) {
                ch.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content().retain()));
            } else if (msg instanceof CloseWebSocketFrame) {
                ch.close();
            }
        }

        private void deliver(final String text) {
            safe(new Runnable() { public void run() { listener.onMessage(RelayConnection.this, text); } });
        }

        private void safe(Runnable r) {
            try { r.run(); } catch (Throwable t) { /* never let a bad message kill the connection */ }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            open = false;
            channel = null;
            safe(new Runnable() { public void run() { listener.onClose(RelayConnection.this); } });
            scheduleReconnect();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}

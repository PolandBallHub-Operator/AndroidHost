package com.genymobile.gnirehtet.relay;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Handle the connections from the clients.
 */
public class TunnelServer {
    private static final String TAG = TunnelServer.class.getSimpleName();
    private final List<Client> clients = new ArrayList<>();
    private final ServerSocketChannel serverSocketChannel;
    public TunnelServer(int port, Selector selector) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        // Use an explicit IPv4 loopback address. On some Android releases the generic
        // loopback helper can resolve through an IPv6/dual-stack path while adb reverse
        // and the self-test use 127.0.0.1.
        serverSocketChannel.socket().bind(new InetSocketAddress("127.0.0.1", port));
        Log.i(TAG, "Relay listening on " + serverSocketChannel.socket().getLocalSocketAddress());

        SelectionHandler socketChannelHandler = (selectionKey) -> {
            try {
                ServerSocketChannel channel = (ServerSocketChannel) selectionKey.channel();
                acceptClient(selector, channel);
            } catch (IOException e) {
                Log.e(TAG, "Cannot accept client", e);
            }
        };
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, socketChannelHandler);
    }

    private void acceptClient(Selector selector, ServerSocketChannel serverSocketChannel) throws IOException {
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false);
        // will register the socket on the selector
        Client client = new Client(selector, socketChannel, this::removeClient);
        clients.add(client);
        Log.i(TAG, "Client #" + client.getId() + " connected");
    }

    private void removeClient(Client client) {
        clients.remove(client);
        Log.i(TAG, "Client #" + client.getId() + " disconnected");
    }

    public void cleanUp() {
        for (Client client : clients) {
            client.cleanExpiredConnections();
        }
    }
    public void close() {
        for (Client client : new ArrayList<>(clients)) {
            client.disconnect();
        }
        try {
            serverSocketChannel.close();
        } catch (IOException e) {
            Log.e(TAG, "Cannot close relay server socket", e);
        }
    }
}

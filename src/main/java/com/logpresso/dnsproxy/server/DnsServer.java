package com.logpresso.dnsproxy.server;

import com.logpresso.dnsproxy.cache.DnsCache;
import com.logpresso.dnsproxy.client.UpstreamResolver;
import com.logpresso.dnsproxy.config.ResolvedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DnsServer implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(DnsServer.class);

    private static final int DEFAULT_DNS_PORT = 53;
    private static final int UDP_BUFFER_SIZE = 4096;
    private static final int UDP_MAX_RESPONSE_SIZE = 512;

    // Thread pool configuration
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 100;
    private static final int QUEUE_CAPACITY = 1000;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final ResolvedConfig config;
    private final DnsHandler handler;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final List<DatagramSocket> udpSockets = new CopyOnWriteArrayList<>();
    private final List<ServerSocket> tcpSockets = new CopyOnWriteArrayList<>();

    public DnsServer(ResolvedConfig config) {
        this.config = config;

        UpstreamResolver resolver = new UpstreamResolver(config);
        DnsCache cache = new DnsCache();
        this.handler = new DnsHandler(resolver, cache, config.isCache());

        AtomicInteger threadCounter = new AtomicInteger(1);
        this.executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "dns-worker-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void start() throws IOException {
        if (!config.isDnsStubListener()) {
            logger.info("logpresso dnsproxy: DNSStubListener is disabled, not starting server");
            return;
        }

        running.set(true);

        String bindAddress = config.getBindAddress();

        startUdpServer(bindAddress, DEFAULT_DNS_PORT);
        startTcpServer(bindAddress, DEFAULT_DNS_PORT);

        for (String extra : config.getDnsStubListenerExtra()) {
            String address = parseAddress(extra);
            startUdpServer(address, DEFAULT_DNS_PORT);
            startTcpServer(address, DEFAULT_DNS_PORT);
        }

        logger.info("logpresso dnsproxy: DNS server started on {}:{}", bindAddress, DEFAULT_DNS_PORT);
    }

    private void startUdpServer(String address, int port) throws IOException {
        InetAddress bindAddr = InetAddress.getByName(address);
        DatagramSocket socket = new DatagramSocket(port, bindAddr);
        udpSockets.add(socket);

        executor.submit(() -> {
            logger.info("logpresso dnsproxy: UDP server listening on {}:{}", address, port);
            while (running.get()) {
                try {
                    byte[] buffer = new byte[UDP_BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    executor.submit(() -> handleUdpRequest(socket, packet));
                } catch (SocketException e) {
                    if (running.get())
                        logger.error("logpresso dnsproxy: UDP socket error", e);
                } catch (IOException e) {
                    logger.error("logpresso dnsproxy: UDP receive error", e);
                }
            }
        });
    }

    private void handleUdpRequest(DatagramSocket socket, DatagramPacket packet) {
        try {
            byte[] queryData = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, queryData, 0, packet.getLength());

            byte[] responseData = handler.handle(queryData, UDP_MAX_RESPONSE_SIZE);
            if (responseData != null) {
                DatagramPacket response = new DatagramPacket(
                        responseData, responseData.length,
                        packet.getAddress(), packet.getPort());
                socket.send(response);
            }
        } catch (IOException e) {
            logger.error("logpresso dnsproxy: Failed to handle UDP request", e);
        }
    }

    private void startTcpServer(String address, int port) throws IOException {
        InetAddress bindAddr = InetAddress.getByName(address);
        ServerSocket socket = new ServerSocket(port, 50, bindAddr);
        tcpSockets.add(socket);

        executor.submit(() -> {
            logger.info("logpresso dnsproxy: TCP server listening on {}:{}", address, port);
            while (running.get()) {
                try {
                    Socket client = socket.accept();
                    executor.submit(() -> handleTcpConnection(client));
                } catch (SocketException e) {
                    if (running.get())
                        logger.error("logpresso dnsproxy: TCP socket error", e);
                } catch (IOException e) {
                    logger.error("logpresso dnsproxy: TCP accept error", e);
                }
            }
        });
    }

    private void handleTcpConnection(Socket client) {
        try (client) {
            client.setSoTimeout(5000);

            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            while (running.get()) {
                int length;
                try {
                    length = in.readUnsignedShort();
                } catch (IOException e) {
                    break;
                }

	            byte[] queryData = new byte[length];
                in.readFully(queryData);

                byte[] responseData = handler.handle(queryData);
                if (responseData != null) {
                    out.writeShort(responseData.length);
                    out.write(responseData);
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (logger.isDebugEnabled())
                logger.debug("logpresso dnsproxy: TCP connection closed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false))
            return; // 이미 close됨

        for (DatagramSocket socket : udpSockets) {
            if (socket != null && !socket.isClosed())
                socket.close();
        }

        udpSockets.clear();

        for (ServerSocket socket : tcpSockets) {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("logpresso dnsproxy: Failed to close TCP socket", e);
                }
            }
        }

        tcpSockets.clear();
        executor.shutdownNow();
        logger.info("logpresso dnsproxy: DNS server stopped");
    }

    private String parseAddress(String input) {
        if (input.startsWith("[")) {
            int closeBracket = input.indexOf(']');
            if (closeBracket == -1)
                return input.substring(1);
            return input.substring(1, closeBracket);
        }

        return input;
    }

}

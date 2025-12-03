package com.logpresso.dnsproxy.client;

import com.logpresso.dnsproxy.config.ResolvedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.List;

public class UpstreamResolver {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamResolver.class);

    private final List<String> primaryServers;
    private final List<String> fallbackServers;

    private static final int DNS_PORT = 53;
    private static final int TIMEOUT_MS = 2000;
    private static final int UDP_MAX_SIZE = 4096;

    public UpstreamResolver(ResolvedConfig config) {
        this.primaryServers = config.getDns();
        this.fallbackServers = config.getFallbackDns();
    }

    public Message resolve(Message query) throws IOException {
        Message response = tryResolve(query, primaryServers);
        if (response != null)
            return response;

        logger.warn("logpresso dnsproxy: All primary DNS servers failed, trying fallback servers");

        response = tryResolve(query, fallbackServers);
        if (response != null)
            return response;

        throw new IOException("All DNS servers failed to respond");
    }

    private Message tryResolve(Message query, List<String> servers) {
        for (String server : servers) {
            try {
                Message response = resolveUdp(query, server);
                if (response != null) {
                    if (response.getHeader().getFlag(org.xbill.DNS.Flags.TC)) {
                        if (logger.isDebugEnabled())
                            logger.debug("logpresso dnsproxy: Response truncated, retrying with TCP: {}", server);

                        response = resolveTcp(query, server);
                    }

                    return response;
                }
            } catch (IOException e) {
                logger.warn("logpresso dnsproxy: DNS query failed for server {}: {}", server, e.getMessage());
            }
        }

        return null;
    }

    private Message resolveUdp(Message query, String server) throws IOException {
        InetAddress address = parseAddress(server);
        int port = parsePort(server, DNS_PORT);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            byte[] queryData = query.toWire();
            DatagramPacket sendPacket = new DatagramPacket(queryData, queryData.length, address, port);
            socket.send(sendPacket);

            byte[] receiveBuffer = new byte[UDP_MAX_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            byte[] responseData = new byte[receivePacket.getLength()];
            System.arraycopy(receivePacket.getData(), 0, responseData, 0, receivePacket.getLength());

            return new Message(responseData);
        }
    }

    private Message resolveTcp(Message query, String server) throws IOException {
        InetAddress address = parseAddress(server);
        int port = parsePort(server, DNS_PORT);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            byte[] queryData = query.toWire();
            out.writeShort(queryData.length);
            out.write(queryData);
            out.flush();

            int responseLength = in.readUnsignedShort();
            byte[] responseData = new byte[responseLength];
            in.readFully(responseData);

            return new Message(responseData);
        }
    }

    private InetAddress parseAddress(String server) throws UnknownHostException {
        String host;

        if (server.startsWith("[")) {
            // IPv6 형식: [::1] 또는 [::1]:53
            int closeBracket = server.indexOf(']');
            if (closeBracket == -1)
                host = server.substring(1);
            else
                host = server.substring(1, closeBracket);
        } else if (server.contains(":") && server.indexOf(':') != server.lastIndexOf(':')) {
            // 순수 IPv6 주소 (콜론이 여러 개): 2001:db8::1
            host = server;
        } else if (server.contains(":")) {
            // IPv4:port 형식: 8.8.8.8:53
            host = server.substring(0, server.lastIndexOf(':'));
        } else {
            // 순수 IPv4 주소: 8.8.8.8
            host = server;
        }

        return InetAddress.getByName(host);
    }

    private int parsePort(String server, int defaultPort) {
        if (server.startsWith("[")) {
            // IPv6 형식: [::1]:53
            int closeBracket = server.indexOf(']');
            if (closeBracket != -1 && closeBracket + 1 < server.length() && server.charAt(closeBracket + 1) == ':') {
                try {
                    return Integer.parseInt(server.substring(closeBracket + 2));
                } catch (NumberFormatException e) {
                    return defaultPort;
                }
            }
        } else if (server.contains(":") && server.indexOf(':') == server.lastIndexOf(':')) {
            // IPv4:port 형식 (콜론이 하나만 있음)
            try {
                return Integer.parseInt(server.substring(server.lastIndexOf(':') + 1));
            } catch (NumberFormatException e) {
                return defaultPort;
            }
        }

        return defaultPort;
    }

}

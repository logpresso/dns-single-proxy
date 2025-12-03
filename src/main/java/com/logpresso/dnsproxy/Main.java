package com.logpresso.dnsproxy;

import com.logpresso.dnsproxy.config.ResolvedConfig;
import com.logpresso.dnsproxy.config.ResolvedConfigParser;
import com.logpresso.dnsproxy.server.DnsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String configPath = "/etc/systemd/resolved.conf";

        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[i + 1];
                i++;
            } else if (args[i].startsWith("--config=")) {
                configPath = args[i].substring("--config=".length());
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printHelp();
                return;
            }
        }

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configPath);

        logger.info("logpresso dnsproxy: Starting DNS Single Proxy");
        logger.info("logpresso dnsproxy: Primary DNS: {}", config.getDns());
        logger.info("logpresso dnsproxy: Fallback DNS: {}", config.getFallbackDns());
        logger.info("logpresso dnsproxy: Cache enabled: {}", config.isCache());

        try (DnsServer server = new DnsServer(config)) {
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("logpresso dnsproxy: Shutting down DNS Single Proxy");
                server.close();
            }));

            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("logpresso dnsproxy: Failed to start DNS server", e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("DNS Single Proxy");
        System.out.println();
        System.out.println("Usage: java -jar dns-single-proxy.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config <path>  Path to resolved.conf (default: /etc/systemd/resolved.conf)");
        System.out.println("  --help, -h       Show this help message");
        System.out.println();
        System.out.println("Description:");
        System.out.println("  DNS proxy that limits response records to one per type.");
        System.out.println("  This helps avoid __check_pf() calls on macOS.");
    }

}

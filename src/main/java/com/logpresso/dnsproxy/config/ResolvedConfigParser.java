package com.logpresso.dnsproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ResolvedConfigParser {

    private static final Logger logger = LoggerFactory.getLogger(ResolvedConfigParser.class);

    private static final String CONFIG_DROP_IN_DIR = "/etc/systemd/resolved.conf.d";
    private static final String RESOLV_CONF_PATH = "/etc/resolv.conf";

    public ResolvedConfig parse(String configPath) {
        ResolvedConfig.Builder builder = ResolvedConfig.builder();

        Path mainConfig = Paths.get(configPath);
        if (Files.exists(mainConfig))
            parseFile(mainConfig, builder);
        else
            logger.info("logpresso dnsproxy: Config file not found: {}, using defaults", configPath);

        Path dropInDir = Paths.get(CONFIG_DROP_IN_DIR);
        if (Files.exists(dropInDir) && Files.isDirectory(dropInDir))
            parseDropInDirectory(dropInDir, builder);

        // If no DNS= configured, try networkctl status (DHCP-provided DNS)
        if (!builder.hasDns()) {
            parseNetworkctl(builder);
        }

        // If still no DNS, fall back to /etc/resolv.conf (systemd-resolved compatible behavior)
        if (!builder.hasDns()) {
            parseResolvConf(builder);
        }

        ResolvedConfig config = builder.build();
        logger.info("logpresso dnsproxy: Configuration loaded: {}", config);

        return config;
    }

    private void parseDropInDirectory(Path dropInDir, ResolvedConfig.Builder builder) {
        List<Path> confFiles = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dropInDir, "*.conf")) {
            for (Path entry : stream) {
                confFiles.add(entry);
            }
        } catch (IOException e) {
            logger.warn("logpresso dnsproxy: Failed to read drop-in directory: {}", dropInDir, e);
            return;
        }

        confFiles.sort(Comparator.comparing(a -> a.getFileName().toString()));

        for (Path confFile : confFiles) {
            parseFile(confFile, builder);
        }
    }

    private void parseFile(Path path, ResolvedConfig.Builder builder) {
        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Parsing config file: {}", path);

        boolean inResolveSection = false;

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                    continue;

                if (line.startsWith("[")) {
                    inResolveSection = line.equalsIgnoreCase("[Resolve]");
                    continue;
                }

                if (!inResolveSection)
                    continue;

                int eqIndex = line.indexOf('=');
                if (eqIndex == -1)
                    continue;

                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();

                applyConfig(builder, key, value);
            }
        } catch (IOException e) {
            logger.warn("logpresso dnsproxy: Failed to parse config file: {}", path, e);
        }
    }

    private void applyConfig(ResolvedConfig.Builder builder, String key, String value) {
        switch (key) {
            case "DNS": {
                List<String> servers = parseServerList(value);
                if (!servers.isEmpty())
                    builder.dns(servers);
                break;
            }
            case "FallbackDNS": {
                List<String> servers = parseServerList(value);
                if (!servers.isEmpty())
                    builder.fallbackDns(servers);
                break;
            }
            case "Cache":
                builder.cache(parseBoolean(value, true));
                break;
            case "DNSStubListener":
                builder.dnsStubListener(parseBoolean(value, true));
                break;
            case "DNSStubListenerExtra": {
                List<String> extras = parseServerList(value);
                if (!extras.isEmpty())
                    builder.dnsStubListenerExtra(extras);
                break;
            }
            case "BindAddress":
                if (!value.isEmpty())
                    builder.bindAddress(value);
                break;
            default:
                logger.warn("logpresso dnsproxy: Unknown config key: {}", key);
                break;
        }
    }

    private List<String> parseServerList(String value) {
        if (value.isEmpty())
            return List.of();

        return Arrays.stream(value.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value.isEmpty())
            return defaultValue;

        return value.equalsIgnoreCase("yes") ||
               value.equalsIgnoreCase("true") ||
               value.equals("1");
    }

    protected String getResolvConfPath() {
        return RESOLV_CONF_PATH;
    }

    private void parseResolvConf(ResolvedConfig.Builder builder) {
        Path resolvConf = Paths.get(getResolvConfPath());
        if (!Files.exists(resolvConf)) {
            logger.debug("logpresso dnsproxy: {} not found, skipping", RESOLV_CONF_PATH);
            return;
        }

        List<String> nameservers = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(resolvConf)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                    continue;

                // Parse nameserver entries
                if (line.startsWith("nameserver")) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length >= 2 && !parts[1].isEmpty()) {
                        String server = parts[1].trim();
                        // Skip localhost entries (often points to systemd-resolved stub)
                        if (!isLocalhost(server)) {
                            nameservers.add(server);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("logpresso dnsproxy: Failed to parse {}: {}", RESOLV_CONF_PATH, e.getMessage());
            return;
        }

        if (!nameservers.isEmpty()) {
            logger.info("logpresso dnsproxy: No DNS= configured, using {} nameservers from {}",
                    nameservers.size(), RESOLV_CONF_PATH);
            builder.dns(nameservers);
        }
    }

    private boolean isLocalhost(String server) {
        return server.equals("127.0.0.1") ||
               server.equals("127.0.0.53") ||
               server.equals("::1") ||
               server.startsWith("127.");
    }

    private void parseNetworkctl(ResolvedConfig.Builder builder) {
        List<String> dnsServers = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("networkctl", "status");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // Parse "DNS: 10.20.30.40" format
                    if (line.startsWith("DNS:")) {
                        String dnsValue = line.substring(4).trim();
                        if (!dnsValue.isEmpty() && !isLocalhost(dnsValue)) {
                            dnsServers.add(dnsValue);
                        }
                    }
                }
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.debug("logpresso dnsproxy: Failed to run networkctl status: {}", e.getMessage());
            return;
        }

        if (!dnsServers.isEmpty()) {
            logger.info("logpresso dnsproxy: No DNS= configured, using {} DNS servers from networkctl",
                    dnsServers.size());
            builder.dns(dnsServers);
        }
    }

}

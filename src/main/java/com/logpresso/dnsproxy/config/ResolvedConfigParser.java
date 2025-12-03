package com.logpresso.dnsproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
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

}

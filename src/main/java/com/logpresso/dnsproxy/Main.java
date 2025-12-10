package com.logpresso.dnsproxy;

import com.logpresso.dnsproxy.config.ResolvedConfig;
import com.logpresso.dnsproxy.config.ResolvedConfigParser;
import com.logpresso.dnsproxy.server.DnsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = loadVersion();
    private static final String INSTALL_DIR = "/opt/dns-single-proxy";
    private static final String SERVICE_NAME = "dns-single-proxy";
    private static final String JAR_NAME = "dns-single-proxy.jar";
    private static final String DEFAULT_JAVA_PATH = "/usr/bin/java";

    public static void main(String[] args) {
        String configPath = "/etc/systemd/resolved.conf";
        String javaPath = DEFAULT_JAVA_PATH;

        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[i + 1];
                i++;
            } else if (args[i].startsWith("--config=")) {
                configPath = args[i].substring("--config=".length());
            } else if ("--java".equals(args[i]) && i + 1 < args.length) {
                javaPath = args[i + 1];
                i++;
            } else if (args[i].startsWith("--java=")) {
                javaPath = args[i].substring("--java=".length());
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printHelp();
                return;
            } else if ("--version".equals(args[i]) || "-v".equals(args[i])) {
                printVersion();
                return;
            } else if ("--install".equals(args[i])) {
                install(javaPath);
                return;
            } else if ("--uninstall".equals(args[i])) {
                uninstall();
                return;
            }
        }

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config;
        try {
            config = parser.parse(configPath);
        } catch (IllegalStateException e) {
            logger.error("logpresso dnsproxy: Configuration error - {}", e.getMessage());
            System.exit(1);
            return;
        }

        logger.info("logpresso dnsproxy: Starting DNS Single Proxy");
        if (config.hasWarning()) {
            logger.warn("logpresso dnsproxy: {}", config.getWarning());
        }
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
        System.out.println("DNS Single Proxy v" + VERSION);
        System.out.println();
        System.out.println("Usage: java -jar dns-single-proxy.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config <path>  Path to resolved.conf (default: /etc/systemd/resolved.conf)");
        System.out.println("  --install        Install as systemd service (stops systemd-resolved)");
        System.out.println("  --java <path>    Java executable path for systemd service (default: /usr/bin/java)");
        System.out.println("                   Only used with --install");
        System.out.println("  --uninstall      Uninstall service and restore systemd-resolved");
        System.out.println("  --version, -v    Show version information");
        System.out.println("  --help, -h       Show this help message");
        System.out.println();
        System.out.println("Description:");
        System.out.println("  DNS proxy that limits response records to one per type.");
        System.out.println("  This helps avoid __check_pf() calls on macOS.");
    }

    private static void printVersion() {
        System.out.println("DNS Single Proxy v" + VERSION);
    }

    private static String loadVersion() {
        try (InputStream is = Main.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String version = props.getProperty("version");
                if (version != null && !version.isEmpty() && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException e) {
            // Ignore and use fallback
        }
        return "unknown";
    }

    private static void install(String javaPath) {
        System.out.println("Installing DNS Single Proxy as systemd service...");

        try {
            // Validate Java executable path first (user can fix this without sudo)
            Path javaExecutable = Paths.get(javaPath);
            if (!Files.exists(javaExecutable)) {
                System.err.println("Error: Java executable not found: " + javaPath);
                System.exit(1);
            }
            if (!Files.isExecutable(javaExecutable)) {
                System.err.println("Error: Java path is not executable: " + javaPath);
                System.exit(1);
            }

            // Check if running as root
            if (!isRoot()) {
                System.err.println("Error: --install requires root privileges. Run with sudo.");
                System.exit(1);
            }

            // Find the current JAR file
            Path jarPath = findCurrentJar();
            if (jarPath == null) {
                System.err.println("Error: Could not determine the JAR file location.");
                System.exit(1);
            }

            // Step 1: Stop and disable systemd-resolved (if it exists)
            if (isServiceExists("systemd-resolved")) {
                System.out.println("Stopping systemd-resolved...");
                runCommandIgnoreError("systemctl", "stop", "systemd-resolved");
                runCommandIgnoreError("systemctl", "disable", "systemd-resolved");
            } else {
                System.out.println("systemd-resolved not found, skipping...");
            }

            // Step 2: Create installation directory
            Path installDir = Paths.get(INSTALL_DIR);
            System.out.println("Creating installation directory: " + installDir);
            Files.createDirectories(installDir);

            // Step 3: Copy JAR file
            Path targetJar = installDir.resolve(JAR_NAME);
            System.out.println("Copying JAR to: " + targetJar);
            Files.copy(jarPath, targetJar, StandardCopyOption.REPLACE_EXISTING);

            // Step 4: Create systemd service file
            Path serviceFile = Paths.get("/etc/systemd/system/" + SERVICE_NAME + ".service");
            System.out.println("Creating systemd service file: " + serviceFile);
            System.out.println("Using Java: " + javaPath);
            String serviceContent = createServiceFileContent(javaPath);
            Files.writeString(serviceFile, serviceContent);

            // Step 5: Reload systemd and enable service
            boolean isUpgrade = isServiceExists(SERVICE_NAME);
            System.out.println(isUpgrade ? "Upgrading service..." : "Enabling and starting service...");
            runCommand("systemctl", "daemon-reload");
            runCommand("systemctl", "enable", SERVICE_NAME);
            runCommand("systemctl", "restart", SERVICE_NAME);

            System.out.println();
            System.out.println(isUpgrade ? "Upgrade complete!" : "Installation complete!");
            System.out.println("  Service status: systemctl status " + SERVICE_NAME);
            System.out.println("  View logs:      journalctl -u " + SERVICE_NAME + " -f");

        } catch (Exception e) {
            System.err.println("Installation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void uninstall() {
        System.out.println("Uninstalling DNS Single Proxy...");

        try {
            // Check if running as root
            if (!isRoot()) {
                System.err.println("Error: --uninstall requires root privileges. Run with sudo.");
                System.exit(1);
            }

            // Step 1: Stop and disable dns-single-proxy
            System.out.println("Stopping dns-single-proxy service...");
            runCommandIgnoreError("systemctl", "stop", SERVICE_NAME);
            runCommandIgnoreError("systemctl", "disable", SERVICE_NAME);

            // Step 2: Remove service file
            Path serviceFile = Paths.get("/etc/systemd/system/" + SERVICE_NAME + ".service");
            if (Files.exists(serviceFile)) {
                System.out.println("Removing service file: " + serviceFile);
                Files.delete(serviceFile);
            }

            // Step 3: Reload systemd
            runCommand("systemctl", "daemon-reload");

            // Step 4: Remove installation directory
            Path installDir = Paths.get(INSTALL_DIR);
            if (Files.exists(installDir)) {
                System.out.println("Removing installation directory: " + installDir);
                deleteDirectory(installDir);
            }

            // Step 5: Re-enable and start systemd-resolved (if it exists)
            if (isServiceExists("systemd-resolved")) {
                System.out.println("Restoring systemd-resolved...");
                runCommandIgnoreError("systemctl", "enable", "systemd-resolved");
                runCommandIgnoreError("systemctl", "start", "systemd-resolved");
                System.out.println();
                System.out.println("Uninstallation complete!");
                System.out.println("systemd-resolved has been restored.");
            } else {
                System.out.println();
                System.out.println("Uninstallation complete!");
                System.out.println("Note: systemd-resolved not found, no DNS service restored.");
            }

        } catch (Exception e) {
            System.err.println("Uninstallation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean isRoot() {
        String user = System.getProperty("user.name");
        return "root".equals(user);
    }

    private static boolean isServiceExists(String serviceName) {
        try {
            // 'systemctl cat' returns exit code 0 if service exists, non-zero otherwise
            ProcessBuilder pb = new ProcessBuilder("systemctl", "cat", serviceName + ".service");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path findCurrentJar() {
        try {
            // Try to get the JAR path from the class location
            String jarPath = Main.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();

            Path path = Paths.get(jarPath);
            if (Files.exists(path) && jarPath.endsWith(".jar")) {
                return path;
            }

            // Fallback: look for the JAR in the current directory
            Path currentDir = Paths.get(System.getProperty("user.dir"));
            Path targetJar = currentDir.resolve("target/dns-single-proxy.jar");
            if (Files.exists(targetJar)) {
                return targetJar;
            }

            // Look in current directory
            Path directJar = currentDir.resolve("dns-single-proxy.jar");
            if (Files.exists(directJar)) {
                return directJar;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String createServiceFileContent(String javaPath) {
        return "[Unit]\n" +
               "Description=DNS Single Proxy\n" +
               "Documentation=https://github.com/logpresso/dns-single-proxy\n" +
               "After=network.target\n" +
               "Before=nss-lookup.target\n" +
               "Wants=nss-lookup.target\n" +
               "\n" +
               "[Service]\n" +
               "Type=simple\n" +
               "ExecStart=" + javaPath + " -jar " + INSTALL_DIR + "/" + JAR_NAME + "\n" +
               "Restart=always\n" +
               "RestartSec=5\n" +
               "AmbientCapabilities=CAP_NET_BIND_SERVICE\n" +
               "\n" +
               "[Install]\n" +
               "WantedBy=multi-user.target\n";
    }

    private static void runCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
    }

    private static void runCommandIgnoreError(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            // Ignore errors
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        Files.walk(directory)
                .sorted((a, b) -> b.compareTo(a)) // Reverse order for depth-first deletion
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Warning: Could not delete " + path);
                    }
                });
    }

}

package com.logpresso.dnsproxy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedConfigParserTest {

    @TempDir
    Path tempDir;

    @Test
    void testParseDefaultValues() {
        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse("/non/existent/path");

        assertEquals(List.of("8.8.8.8"), config.getDns());
        assertEquals(List.of("1.1.1.1"), config.getFallbackDns());
        assertTrue(config.isCache());
        assertTrue(config.isDnsStubListener());
        assertTrue(config.getDnsStubListenerExtra().isEmpty());
    }

    @Test
    void testParseConfigFile() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Resolve]\n" +
                "DNS=1.2.3.4 5.6.7.8\n" +
                "FallbackDNS=9.10.11.12\n" +
                "Cache=no\n" +
                "DNSStubListener=yes\n" +
                "DNSStubListenerExtra=0.0.0.0:5353\n");

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configFile.toString());

        assertEquals(List.of("1.2.3.4", "5.6.7.8"), config.getDns());
        assertEquals(List.of("9.10.11.12"), config.getFallbackDns());
        assertFalse(config.isCache());
        assertTrue(config.isDnsStubListener());
        assertEquals(List.of("0.0.0.0:5353"), config.getDnsStubListenerExtra());
    }

    @Test
    void testParseIgnoresComments() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "# This is a comment\n" +
                "[Resolve]\n" +
                "; Another comment\n" +
                "DNS=1.1.1.1\n" +
                "# DNS=2.2.2.2\n");

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configFile.toString());

        assertEquals(List.of("1.1.1.1"), config.getDns());
    }

    @Test
    void testParseIgnoresOtherSections() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Other]\n" +
                "DNS=9.9.9.9\n" +
                "\n" +
                "[Resolve]\n" +
                "DNS=1.1.1.1\n" +
                "\n" +
                "[Another]\n" +
                "DNS=8.8.8.8\n");

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configFile.toString());

        assertEquals(List.of("1.1.1.1"), config.getDns());
    }

    @Test
    void testParseBooleanValues() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");

        Files.writeString(configFile, "[Resolve]\nCache=yes\n");
        assertTrue(new ResolvedConfigParser().parse(configFile.toString()).isCache());

        Files.writeString(configFile, "[Resolve]\nCache=true\n");
        assertTrue(new ResolvedConfigParser().parse(configFile.toString()).isCache());

        Files.writeString(configFile, "[Resolve]\nCache=1\n");
        assertTrue(new ResolvedConfigParser().parse(configFile.toString()).isCache());

        Files.writeString(configFile, "[Resolve]\nCache=no\n");
        assertFalse(new ResolvedConfigParser().parse(configFile.toString()).isCache());

        Files.writeString(configFile, "[Resolve]\nCache=false\n");
        assertFalse(new ResolvedConfigParser().parse(configFile.toString()).isCache());
    }

    @Test
    void testParseMultipleDnsLines() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Resolve]\n" +
                "DNS=1.1.1.1\n" +
                "DNS=8.8.8.8\n" +
                "DNS=9.9.9.9\n" +
                "FallbackDNS=1.0.0.1\n" +
                "FallbackDNS=8.8.4.4\n");

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configFile.toString());

        // Multiple lines should accumulate
        assertEquals(List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"), config.getDns());
        assertEquals(List.of("1.0.0.1", "8.8.4.4"), config.getFallbackDns());
    }

    @Test
    void testParseMixedDnsFormat() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Resolve]\n" +
                "DNS=1.1.1.1 8.8.8.8\n" +
                "DNS=9.9.9.9\n" +
                "FallbackDNS=1.0.0.1 8.8.4.4\n" +
                "FallbackDNS=4.4.4.4\n");

        ResolvedConfigParser parser = new ResolvedConfigParser();
        ResolvedConfig config = parser.parse(configFile.toString());

        // Space-separated and multiple lines should all accumulate
        assertEquals(List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"), config.getDns());
        assertEquals(List.of("1.0.0.1", "8.8.4.4", "4.4.4.4"), config.getFallbackDns());
    }

    @Test
    void testFallbackToResolvConf() throws IOException {
        // Create resolved.conf without DNS= setting
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Resolve]\n" +
                "FallbackDNS=8.8.8.8\n" +
                "Cache=yes\n");

        // Create mock resolv.conf
        Path resolvConf = tempDir.resolve("resolv.conf");
        Files.writeString(resolvConf,
                "# Generated by NetworkManager\n" +
                "nameserver 172.20.0.2\n" +
                "nameserver 172.20.0.5\n" +
                "search example.com\n");

        // Use a custom parser that reads from our test resolv.conf
        ResolvedConfigParser parser = new TestableResolvedConfigParser(resolvConf.toString());
        ResolvedConfig config = parser.parse(configFile.toString());

        // Should use nameservers from resolv.conf since DNS= is not set
        assertEquals(List.of("172.20.0.2", "172.20.0.5"), config.getDns());
        assertEquals(List.of("8.8.8.8"), config.getFallbackDns());
    }

    @Test
    void testResolvConfSkipsLocalhost() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile, "[Resolve]\nCache=yes\n");

        Path resolvConf = tempDir.resolve("resolv.conf");
        Files.writeString(resolvConf,
                "nameserver 127.0.0.53\n" +  // systemd-resolved stub - should skip
                "nameserver 127.0.0.1\n" +   // localhost - should skip
                "nameserver 8.8.8.8\n");     // real DNS - should use

        ResolvedConfigParser parser = new TestableResolvedConfigParser(resolvConf.toString());
        ResolvedConfig config = parser.parse(configFile.toString());

        assertEquals(List.of("8.8.8.8"), config.getDns());
    }

    @Test
    void testDnsSettingTakesPrecedenceOverResolvConf() throws IOException {
        Path configFile = tempDir.resolve("resolved.conf");
        Files.writeString(configFile,
                "[Resolve]\n" +
                "DNS=1.1.1.1\n");

        Path resolvConf = tempDir.resolve("resolv.conf");
        Files.writeString(resolvConf, "nameserver 8.8.8.8\n");

        ResolvedConfigParser parser = new TestableResolvedConfigParser(resolvConf.toString());
        ResolvedConfig config = parser.parse(configFile.toString());

        // DNS= setting should take precedence, resolv.conf should not be used
        assertEquals(List.of("1.1.1.1"), config.getDns());
    }

    /**
     * Test helper class that allows overriding the resolv.conf path
     */
    static class TestableResolvedConfigParser extends ResolvedConfigParser {
        private final String resolvConfPath;

        TestableResolvedConfigParser(String resolvConfPath) {
            this.resolvConfPath = resolvConfPath;
        }

        @Override
        protected String getResolvConfPath() {
            return resolvConfPath;
        }
    }

}

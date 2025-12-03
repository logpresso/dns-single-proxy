package com.logpresso.dnsproxy.config;

import java.util.List;

public final class ResolvedConfig {

    private final List<String> dns;
    private final List<String> fallbackDns;
    private final boolean cache;
    private final boolean dnsStubListener;
    private final List<String> dnsStubListenerExtra;
    private final String bindAddress;

    private ResolvedConfig(Builder builder) {
        this.dns = List.copyOf(builder.dns);
        this.fallbackDns = List.copyOf(builder.fallbackDns);
        this.cache = builder.cache;
        this.dnsStubListener = builder.dnsStubListener;
        this.dnsStubListenerExtra = List.copyOf(builder.dnsStubListenerExtra);
        this.bindAddress = builder.bindAddress;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> getDns() {
        return dns;
    }

    public List<String> getFallbackDns() {
        return fallbackDns;
    }

    public boolean isCache() {
        return cache;
    }

    public boolean isDnsStubListener() {
        return dnsStubListener;
    }

    public List<String> getDnsStubListenerExtra() {
        return dnsStubListenerExtra;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    @Override
    public String toString() {
        return "ResolvedConfig{" +
                "dns=" + dns +
                ", fallbackDns=" + fallbackDns +
                ", cache=" + cache +
                ", dnsStubListener=" + dnsStubListener +
                ", dnsStubListenerExtra=" + dnsStubListenerExtra +
                ", bindAddress=" + bindAddress +
                '}';
    }

    public static final class Builder {
        private List<String> dns = List.of("8.8.8.8");
        private List<String> fallbackDns = List.of("1.1.1.1");
        private boolean cache = true;
        private boolean dnsStubListener = true;
        private List<String> dnsStubListenerExtra = List.of();
        private String bindAddress = "127.0.0.53";

        private Builder() {}

        public Builder dns(List<String> dns) {
            this.dns = dns;
            return this;
        }

        public Builder fallbackDns(List<String> fallbackDns) {
            this.fallbackDns = fallbackDns;
            return this;
        }

        public Builder cache(boolean cache) {
            this.cache = cache;
            return this;
        }

        public Builder dnsStubListener(boolean dnsStubListener) {
            this.dnsStubListener = dnsStubListener;
            return this;
        }

        public Builder dnsStubListenerExtra(List<String> dnsStubListenerExtra) {
            this.dnsStubListenerExtra = dnsStubListenerExtra;
            return this;
        }

        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        public ResolvedConfig build() {
            return new ResolvedConfig(this);
        }
    }

}

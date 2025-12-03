package com.logpresso.dnsproxy.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DnsCache {

    private static final Logger logger = LoggerFactory.getLogger(DnsCache.class);

    private static final int DEFAULT_MAX_ENTRIES = 10000;
    private static final long NEGATIVE_CACHE_TTL_SECONDS = 30;
    private static final int EVICTION_BATCH_SIZE = 100;
    private static final long TTL_ADJUSTMENT_INTERVAL_MS = 1000; // 1초마다 TTL 재조정

    private final int maxEntries;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicInteger evictionCounter = new AtomicInteger(0);

    public DnsCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public DnsCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public Message get(String qname, int qtype, int qclass) {
        String key = buildKey(qname, qtype, qclass);
        CacheEntry entry = cache.get(key);

        if (entry == null)
            return null;

        if (entry.isExpired()) {
            cache.remove(key, entry);
            if (logger.isDebugEnabled())
                logger.debug("logpresso dnsproxy: Cache entry expired: {}", key);

            return null;
        }

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Cache hit: {}", key);

        return entry.getAdjustedMessage();
    }

    public void put(String qname, int qtype, int qclass, Message message, boolean isNxDomain) {
        String key = buildKey(qname, qtype, qclass);
        long ttlSeconds;

        if (isNxDomain) {
            ttlSeconds = NEGATIVE_CACHE_TTL_SECONDS;
        } else {
            ttlSeconds = getMinTtl(message);
            if (ttlSeconds <= 0) {
                if (logger.isDebugEnabled())
                    logger.debug("logpresso dnsproxy: Not caching response with TTL <= 0: {}", key);

                return;
            }
        }

        if (evictionCounter.incrementAndGet() % EVICTION_BATCH_SIZE == 0)
            evictExpiredEntries();

        if (cache.size() >= maxEntries)
            evictExpiredEntries();

        if (cache.size() >= maxEntries)
            evictOldestEntries();

        CacheEntry entry = new CacheEntry(message, ttlSeconds);
        cache.put(key, entry);

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Cached response: {} (TTL: {}s)", key, ttlSeconds);
    }

    public void clear() {
        cache.clear();
        logger.info("logpresso dnsproxy: Cache cleared");
    }

    public int size() {
        return cache.size();
    }

    private String buildKey(String qname, int qtype, int qclass) {
        return qname.toLowerCase() + ":" + qtype + ":" + qclass;
    }

    private long getMinTtl(Message message) {
        long minTtl = Long.MAX_VALUE;

        for (int section : new int[]{Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL}) {
            for (Record record : message.getSection(section)) {
                long ttl = record.getTTL();
                if (ttl < minTtl)
                    minTtl = ttl;
            }
        }

        return minTtl == Long.MAX_VALUE ? 0 : minTtl;
    }

    private void evictExpiredEntries() {
        int evicted = 0;
        Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                evicted++;
            }
        }

        if (evicted > 0 && logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Evicted {} expired cache entries", evicted);
    }

    private void evictOldestEntries() {
        int targetEviction = Math.max(1, maxEntries / 10);
        int evicted = 0;

        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparingLong(a -> a.getValue().getCreationTime()));

        for (Map.Entry<String, CacheEntry> entry : entries) {
            if (evicted >= targetEviction)
                break;

            cache.remove(entry.getKey(), entry.getValue());
            evicted++;
        }

        if (evicted > 0 && logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Evicted {} oldest cache entries", evicted);
    }

    private static class CacheEntry {
        private final Message originalMessage;
        private final long creationTime;
        private final long expirationTime;

        // TTL 조정 캐싱
        private volatile Message cachedAdjustedMessage;
        private volatile long lastAdjustmentTime;

        CacheEntry(Message message, long ttlSeconds) {
            this.originalMessage = message;
            this.creationTime = System.currentTimeMillis();
            this.expirationTime = creationTime + (ttlSeconds * 1000);
            this.lastAdjustmentTime = 0;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        long getCreationTime() {
            return creationTime;
        }

        Message getAdjustedMessage() {
            long now = System.currentTimeMillis();

            // 1초 이내에 조정된 캐시가 있으면 재사용
            if (cachedAdjustedMessage != null && (now - lastAdjustmentTime) < TTL_ADJUSTMENT_INTERVAL_MS)
                return cachedAdjustedMessage;

            // TTL 조정 수행
            Message adjusted = adjustTtl(now);
            cachedAdjustedMessage = adjusted;
            lastAdjustmentTime = now;

            return adjusted;
        }

        private Message adjustTtl(long now) {
            long elapsedSeconds = (now - creationTime) / 1000;

            Message adjusted = originalMessage.clone();

            for (int section : new int[]{Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL}) {
                List<Record> records = adjusted.getSection(section);
                if (records == null || records.isEmpty())
                    continue;

                List<Record> adjustedRecords = new ArrayList<>();
                for (Record record : records) {
                    long newTtl = Math.max(0, record.getTTL() - elapsedSeconds);
                    Record adjustedRecord = Record.newRecord(
                            record.getName(),
                            record.getType(),
                            record.getDClass(),
                            newTtl,
                            record.rdataToWireCanonical()
                    );
                    adjustedRecords.add(adjustedRecord);
                }

                adjusted.removeAllRecords(section);
                for (Record record : adjustedRecords) {
                    adjusted.addRecord(record, section);
                }
            }

            return adjusted;
        }
    }

}

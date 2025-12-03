package com.logpresso.dnsproxy.cache;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class DnsCacheTest {

    @Test
    void testCacheHitAndMiss() throws IOException {
        DnsCache cache = new DnsCache();

        Message response = createResponse("example.com", "1.1.1.1", 300);
        cache.put("example.com.", Type.A, DClass.IN, response, false);

        Message cached = cache.get("example.com.", Type.A, DClass.IN);
        assertNotNull(cached);

        Message missed = cache.get("other.com.", Type.A, DClass.IN);
        assertNull(missed);
    }

    @Test
    void testCacheExpiration() throws IOException, InterruptedException {
        DnsCache cache = new DnsCache();

        Message response = createResponse("example.com", "1.1.1.1", 1);
        cache.put("example.com.", Type.A, DClass.IN, response, false);

        assertNotNull(cache.get("example.com.", Type.A, DClass.IN));

        Thread.sleep(1100);

        assertNull(cache.get("example.com.", Type.A, DClass.IN));
    }

    @Test
    void testNegativeCache() throws IOException, InterruptedException {
        DnsCache cache = new DnsCache();

        Message nxdomain = new Message();
        nxdomain.getHeader().setFlag(Flags.QR);
        nxdomain.getHeader().setRcode(Rcode.NXDOMAIN);

        Name name = Name.fromString("nonexistent.example.com.");
        nxdomain.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);

        cache.put("nonexistent.example.com.", Type.A, DClass.IN, nxdomain, true);

        assertNotNull(cache.get("nonexistent.example.com.", Type.A, DClass.IN));
    }

    @Test
    void testCacheClear() throws IOException {
        DnsCache cache = new DnsCache();

        cache.put("example.com.", Type.A, DClass.IN,
                createResponse("example.com", "1.1.1.1", 300), false);
        cache.put("other.com.", Type.A, DClass.IN,
                createResponse("other.com", "2.2.2.2", 300), false);

        assertEquals(2, cache.size());

        cache.clear();

        assertEquals(0, cache.size());
        assertNull(cache.get("example.com.", Type.A, DClass.IN));
    }

    @Test
    void testOldestEviction() throws IOException {
        // ConcurrentHashMap 기반 구현은 creation time 기준으로 오래된 항목을 제거
        DnsCache cache = new DnsCache(3);

        cache.put("a.com.", Type.A, DClass.IN,
                createResponse("a.com", "1.1.1.1", 300), false);
        cache.put("b.com.", Type.A, DClass.IN,
                createResponse("b.com", "2.2.2.2", 300), false);
        cache.put("c.com.", Type.A, DClass.IN,
                createResponse("c.com", "3.3.3.3", 300), false);

        // 캐시 가득 참
        assertEquals(3, cache.size());

        // 새 항목 추가 시 가장 오래된 항목(a)이 제거됨
        cache.put("d.com.", Type.A, DClass.IN,
                createResponse("d.com", "4.4.4.4", 300), false);

        assertNull(cache.get("a.com.", Type.A, DClass.IN)); // 가장 먼저 생성된 a가 제거됨
        assertNotNull(cache.get("b.com.", Type.A, DClass.IN));
        assertNotNull(cache.get("c.com.", Type.A, DClass.IN));
        assertNotNull(cache.get("d.com.", Type.A, DClass.IN));
    }

    @Test
    void testCaseInsensitiveKeys() throws IOException {
        DnsCache cache = new DnsCache();

        cache.put("EXAMPLE.COM.", Type.A, DClass.IN,
                createResponse("example.com", "1.1.1.1", 300), false);

        assertNotNull(cache.get("example.com.", Type.A, DClass.IN));
        assertNotNull(cache.get("EXAMPLE.COM.", Type.A, DClass.IN));
        assertNotNull(cache.get("Example.Com.", Type.A, DClass.IN));
    }

    private Message createResponse(String domain, String ip, long ttl) throws IOException {
        Message response = new Message();
        response.getHeader().setFlag(Flags.QR);

        Name name = Name.fromString(domain + ".");
        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);
        response.addRecord(new ARecord(name, DClass.IN, ttl,
                InetAddress.getByName(ip)), Section.ANSWER);

        return response;
    }
}

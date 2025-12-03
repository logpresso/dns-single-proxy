package com.logpresso.dnsproxy.filter;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleRecordFilterTest {

    private final SingleRecordFilter filter = new SingleRecordFilter();

    @Test
    void testFilterMultipleARecords() throws IOException {
        Message response = createResponse("example.com",
                List.of("1.1.1.1", "2.2.2.2", "3.3.3.3"));

        Message filtered = filter.filter(response);

        List<Record> answers = filtered.getSection(Section.ANSWER);
        assertEquals(1, answers.size());
        assertEquals(Type.A, answers.get(0).getType());
    }

    @Test
    void testFilterMixedAAndAAAARecords() throws IOException {
        Message response = new Message();
        response.getHeader().setFlag(Flags.QR);

        Name name = Name.fromString("example.com.");
        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);

        response.addRecord(new ARecord(name, DClass.IN, 300,
                InetAddress.getByName("1.1.1.1")), Section.ANSWER);
        response.addRecord(new ARecord(name, DClass.IN, 300,
                InetAddress.getByName("2.2.2.2")), Section.ANSWER);
        response.addRecord(new AAAARecord(name, DClass.IN, 300,
                InetAddress.getByName("2001:db8::1")), Section.ANSWER);
        response.addRecord(new AAAARecord(name, DClass.IN, 300,
                InetAddress.getByName("2001:db8::2")), Section.ANSWER);

        Message filtered = filter.filter(response);

        List<Record> answers = filtered.getSection(Section.ANSWER);
        assertEquals(2, answers.size());

        long aCount = answers.stream().filter(r -> r.getType() == Type.A).count();
        long aaaaCount = answers.stream().filter(r -> r.getType() == Type.AAAA).count();

        assertEquals(1, aCount);
        assertEquals(1, aaaaCount);
    }

    @Test
    void testFilterCnameChain() throws IOException {
        Message response = new Message();
        response.getHeader().setFlag(Flags.QR);

        Name name = Name.fromString("www.example.com.");
        Name target = Name.fromString("example.com.");

        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);

        response.addRecord(new CNAMERecord(name, DClass.IN, 300, target), Section.ANSWER);
        response.addRecord(new ARecord(target, DClass.IN, 300,
                InetAddress.getByName("1.1.1.1")), Section.ANSWER);
        response.addRecord(new ARecord(target, DClass.IN, 300,
                InetAddress.getByName("2.2.2.2")), Section.ANSWER);

        Message filtered = filter.filter(response);

        List<Record> answers = filtered.getSection(Section.ANSWER);
        assertEquals(2, answers.size());

        long cnameCount = answers.stream().filter(r -> r.getType() == Type.CNAME).count();
        long aCount = answers.stream().filter(r -> r.getType() == Type.A).count();

        assertEquals(1, cnameCount);
        assertEquals(1, aCount);
    }

    @Test
    void testFilterNullResponse() {
        assertNull(filter.filter(null));
    }

    @Test
    void testFilterEmptyAnswers() throws IOException {
        Message response = new Message();
        response.getHeader().setFlag(Flags.QR);

        Name name = Name.fromString("nonexistent.example.com.");
        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);

        Message filtered = filter.filter(response);

        assertTrue(filtered.getSection(Section.ANSWER).isEmpty());
    }

    private Message createResponse(String domain, List<String> ips) throws IOException {
        Message response = new Message();
        response.getHeader().setFlag(Flags.QR);

        Name name = Name.fromString(domain + ".");
        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION);

        for (String ip : ips) {
            response.addRecord(new ARecord(name, DClass.IN, 300,
                    InetAddress.getByName(ip)), Section.ANSWER);
        }

        return response;
    }

}

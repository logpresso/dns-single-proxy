package com.logpresso.dnsproxy.server;

import com.logpresso.dnsproxy.cache.DnsCache;
import com.logpresso.dnsproxy.client.UpstreamResolver;
import com.logpresso.dnsproxy.filter.SingleRecordFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

import java.io.IOException;

public class DnsHandler {

    private static final Logger logger = LoggerFactory.getLogger(DnsHandler.class);

    private final UpstreamResolver resolver;
    private final DnsCache cache;
    private final SingleRecordFilter filter;
    private final boolean cacheEnabled;

    public DnsHandler(UpstreamResolver resolver, DnsCache cache, boolean cacheEnabled) {
        this.resolver = resolver;
        this.cache = cache;
        this.filter = new SingleRecordFilter();
        this.cacheEnabled = cacheEnabled;
    }

    public byte[] createTruncatedResponse(int queryId, Record question) {
        Message truncatedResponse = new Message(queryId);
        truncatedResponse.getHeader().setFlag(Flags.QR);
        truncatedResponse.getHeader().setFlag(Flags.TC);

        if (question != null)
            truncatedResponse.addRecord(question, Section.QUESTION);

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Response truncated for UDP, client should retry with TCP");

        return truncatedResponse.toWire();
    }

    public byte[] handle(byte[] queryData) {
        return handle(queryData, Integer.MAX_VALUE);
    }

    public byte[] handle(byte[] queryData, int maxResponseSize) {
        Message query;
        try {
            query = new Message(queryData);
        } catch (IOException e) {
            logger.warn("logpresso dnsproxy: Failed to parse DNS query", e);
            return null;
        }

        Record question = query.getQuestion();
        if (question == null) {
            logger.warn("logpresso dnsproxy: Query has no question section");
            return createServFail(query).toWire();
        }

        String qname = question.getName().toString();
        int qtype = question.getType();
        int qclass = question.getDClass();
        int queryId = query.getHeader().getID();

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Query: {} {} {}", qname, Type.string(qtype), DClass.string(qclass));

        Message response;

        if (cacheEnabled) {
            response = cache.get(qname, qtype, qclass);
            if (response != null) {
                response = cloneWithId(response, queryId);
                if (logger.isDebugEnabled())
                    logger.debug("logpresso dnsproxy: Cache hit for: {}", qname);

                byte[] responseData = response.toWire();
                if (responseData.length > maxResponseSize)
                    return createTruncatedResponse(queryId, question);

                return responseData;
            }
        }

        try {
            response = resolver.resolve(query);
        } catch (IOException e) {
            logger.error("logpresso dnsproxy: Upstream query failed: {}", e.getMessage());
            return createServFail(query).toWire();
        }

        Message filteredResponse = filter.filter(response);

        if (cacheEnabled) {
            boolean isNxDomain = filteredResponse.getHeader().getRcode() == Rcode.NXDOMAIN;
            cache.put(qname, qtype, qclass, filteredResponse, isNxDomain);
        }

        filteredResponse.getHeader().setID(queryId);

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Response: {} records for {}", filteredResponse.getSection(Section.ANSWER).size(), qname);

        byte[] responseData = filteredResponse.toWire();
        if (responseData.length > maxResponseSize)
            return createTruncatedResponse(queryId, question);

        return responseData;
    }

    private Message createServFail(Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setRcode(Rcode.SERVFAIL);

        Record question = query.getQuestion();
        if (question != null)
            response.addRecord(question, Section.QUESTION);

        return response;
    }

    private Message cloneWithId(Message original, int id) {
        Message cloned = original.clone();
        cloned.getHeader().setID(id);

        return cloned;
    }

}

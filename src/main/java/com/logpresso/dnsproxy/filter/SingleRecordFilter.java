package com.logpresso.dnsproxy.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SingleRecordFilter {

    private static final Logger logger = LoggerFactory.getLogger(SingleRecordFilter.class);

    public Message filter(Message response) {
        if (response == null)
            return null;

        Message filtered = response.clone();

        List<Record> originalAnswers = response.getSection(Section.ANSWER);
        if (originalAnswers == null || originalAnswers.isEmpty())
            return filtered;

        Map<Integer, Record> seen = new HashMap<>();
        List<Record> filteredAnswers = new ArrayList<>();

        for (Record record : originalAnswers) {
            int type = record.getType();
            if (!seen.containsKey(type)) {
                seen.put(type, record);
                filteredAnswers.add(record);
            }
        }

        filtered.removeAllRecords(Section.ANSWER);

        for (Record record : filteredAnswers) {
            filtered.addRecord(record, Section.ANSWER);
        }

        if (logger.isDebugEnabled())
            logger.debug("logpresso dnsproxy: Filtered records: {} -> {} (types: {})",
                    originalAnswers.size(), filteredAnswers.size(), seen.keySet());

        return filtered;
    }

}

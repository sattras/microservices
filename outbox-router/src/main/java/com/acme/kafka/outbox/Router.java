package com.acme.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class Router<R extends ConnectRecord<R>> implements Transformation<R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Router.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Schema SCHEMA_KEY = SchemaBuilder.struct()
            .name("com.acme.kafka.outbox.avro.EventKey")
            .field("eventId", Schema.STRING_SCHEMA)
            .build();

    private static final Schema SCHEMA_VALUE = SchemaBuilder.struct()
            .name("com.acme.kafka.outbox.avro.EventValue")
            .field("eventType", Schema.STRING_SCHEMA)
            .field("timestamp", Schema.INT64_SCHEMA)
            .field("payload", Schema.STRING_SCHEMA)
            .build();


    @Override
    public void configure(Map<String, ?> map) {

    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }

        Struct struct = (Struct) record.value();
        Long ts = struct.getInt64("ts_ms");
        String op = struct.getString("op");
        if (!op.equals("c")) {
            return null;
        }

        Map<String, String> after;
        try {
            after = MAPPER.readValue(struct.getString("after"), Map.class);
        } catch (IOException e) {
            LOGGER.error("error occurred {}, after: {}", e.getMessage(), struct.getString("after"));
            return null;
        }

        String topic = String.format("%s.outbox", after.get("aggregateType"));
        String eventId = after.get("eventId");
        String eventType = after.get("eventType");
        String payload = after.get("payload");

        Struct key = new Struct(SCHEMA_KEY)
                .put("eventId", eventId);

        Struct value = new Struct(SCHEMA_VALUE)
                .put("eventType", eventType)
                .put("timestamp", ts)
                .put("payload", payload);

        Headers headers = record.headers();
        headers.addString("correlationId", eventId);

        LOGGER.debug("transform {}-{} and route to topic: {}", eventId, eventType, topic);
        return record.newRecord(topic, null, SCHEMA_KEY, key, SCHEMA_VALUE, value, ts, headers);
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {

    }

}

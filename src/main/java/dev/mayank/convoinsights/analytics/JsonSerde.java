package dev.mayank.convoinsights.analytics;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/** JSON serde for the analytics topology. Null-safe in both directions, because KTables use null as a tombstone. */
public final class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    public JsonSerde(ObjectMapper mapper, Class<T> type) {
        this.mapper = mapper;
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, value) -> value == null ? null : mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) return null;
            try {
                return mapper.readValue(bytes, type);
            } catch (JacksonException e) {
                throw new SerializationException("cannot read " + type.getSimpleName() + " from " + topic, e);
            }
        };
    }
}

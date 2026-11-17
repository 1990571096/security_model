package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/**
 * Serializes Long/long bean properties whose names end in {@code Id} as decimal strings.
 * Non-identifier longs (progress counters, durations) remain JSON numbers.
 */
@Configuration
public class IdJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer idStringSerializerCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            SimpleModule module = new SimpleModule("security-model-id-serialization");
            module.setSerializerModifier(new IdBeanSerializerModifier());
            mapper.registerModule(module);
        });
    }

    static class IdBeanSerializerModifier extends BeanSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                        BeanDescription beanDesc,
                                                        List<BeanPropertyWriter> beanProperties) {
            for (BeanPropertyWriter writer : beanProperties) {
                Class<?> raw = writer.getType().getRawClass();
                if (IdJsonSupport.isIdField(writer.getName())
                        && (raw == Long.class || raw == long.class)) {
                    writer.assignSerializer(ToStringSerializer.instance);
                } else if (raw != null && JsonNode.class.isAssignableFrom(raw)) {
                    writer.assignSerializer(IdJsonNodeSerializer.instance);
                }
            }
            return beanProperties;
        }
    }

    static class IdJsonNodeSerializer extends JsonSerializer<Object> {
        static final IdJsonNodeSerializer instance = new IdJsonNodeSerializer();

        @Override
        public void serialize(Object value, JsonGenerator generator,
                              SerializerProvider serializers) throws IOException {
            if (!(value instanceof JsonNode node) || node.isNull()) {
                generator.writeNull();
                return;
            }
            serializers.defaultSerializeValue(IdJsonSupport.normalize(node), generator);
        }
    }
}

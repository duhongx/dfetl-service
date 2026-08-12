package com.dfygt.dfetl.server.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.io.IOException;

/**
 * 修复 Spring Boot 3.x 默认序列化 {@link Page} 时丢失 totalElements/totalPages/size 等元数据的问题。
 * <p>
 * 通过 {@link Jackson2ObjectMapperBuilderCustomizer} 注入到 Web MVC 用的 ObjectMapper，
 * 同时绑定 {@link PageImpl} 具体类与 {@link Page} 接口，保证不同返回类型都能命中。
 */
@Configuration
public class PageJacksonSerializerConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer pageJacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("PageJacksonModule");
            PageSerializer ser = new PageSerializer();
            module.addSerializer(PageImpl.class, ser);
            module.addSerializer(Page.class, ser);
            builder.modulesToInstall(module);
        };
    }

    @SuppressWarnings({"rawtypes", "serial"})
    private static final class PageSerializer extends JsonSerializer<Page> {
        @Override
        public void serialize(Page page, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeFieldName("content");
            provider.defaultSerializeValue(page.getContent(), gen);
            gen.writeNumberField("totalElements", page.getTotalElements());
            gen.writeNumberField("totalPages", page.getTotalPages());
            gen.writeNumberField("number", page.getNumber());
            gen.writeNumberField("size", page.getSize());
            gen.writeNumberField("numberOfElements", page.getNumberOfElements());
            gen.writeBooleanField("first", page.isFirst());
            gen.writeBooleanField("last", page.isLast());
            gen.writeBooleanField("empty", page.isEmpty());
            gen.writeEndObject();
        }
    }
}

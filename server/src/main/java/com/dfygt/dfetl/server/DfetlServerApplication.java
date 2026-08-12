package com.dfygt.dfetl.server;

import com.dfygt.dfetl.server.config.DataSourcePoolProperties;
import com.dfygt.dfetl.server.config.DiffProperties;
import com.dfygt.dfetl.server.engine.checksum.ChecksumProperties;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {RabbitAutoConfiguration.class})
@EnableJpaAuditing
@EnableScheduling
@EnableConfigurationProperties({SeaTunnelProperties.class, ChecksumProperties.class, DiffProperties.class,
        DataSourcePoolProperties.class})
// 解决 Spring Data 3.x 序列化 PageImpl 的告警，统一改用 PagedModel(VIA_DTO) 输出
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class DfetlServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DfetlServerApplication.class, args);
    }
}

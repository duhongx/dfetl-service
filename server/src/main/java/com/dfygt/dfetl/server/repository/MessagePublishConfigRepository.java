package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessagePublishConfigRepository extends JpaRepository<MessagePublishConfig, Long> {

    Optional<MessagePublishConfig> findByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);

    /** 用于校验同 channel 不可绑定不同 messageType */
    List<MessagePublishConfig> findByChannel(String channel);
}

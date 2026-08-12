package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.AlertChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, Long> {

    List<AlertChannel> findAllByOrderByCreatedAtDesc();
}

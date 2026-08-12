package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立事务中执行单次数据源创建尝试。
 *
 * <p>PostgreSQL 唯一约束冲突后，当前事务会进入 failed 状态，不能在同一事务内继续重试。
 * 外层服务捕获本方法抛出的冲突后，会使用新的实体再次调用本方法。</p>
 */
@Service
@RequiredArgsConstructor
public class SourceDataSourceCreateAttemptService {

    private final SourceDataSourceRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceDataSource saveInNewTransaction(SourceDataSource entity) {
        // 主动 flush，确保唯一约束异常在本次独立事务边界内暴露给外层重试循环。
        return repository.saveAndFlush(entity);
    }
}

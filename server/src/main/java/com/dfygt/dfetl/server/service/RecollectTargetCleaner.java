package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/** 目标表破坏性清理的单一实现，Controller 不直接拼接或执行 SQL。 */
@Service
@RequiredArgsConstructor
public class RecollectTargetCleaner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TargetDataSourceRepository targetDataSourceRepository;
    private final AesUtil aesUtil;

    public int clear(SyncTask task, String mode) {
        var target = targetDataSourceRepository.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "TargetDataSource not found: " + task.getTargetDataSourceId()));
        String database = IdentifierSanitizer.requireValid(target.getDbName(), "tgt.dbName");
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s", target.getFeHost(), target.getFePort(), database);
        String password = aesUtil.decrypt(target.getPasswordEnc());
        List<String> sourceTables = task.getViewNames() == null ? List.of() : task.getViewNames();
        Map<String, String> tableMap = task.getTargetTableMap() == null || task.getTargetTableMap().isBlank()
                ? Map.of()
                : TargetTableMapParser.parseStrict(task.getTargetTableMap(), OBJECT_MAPPER, task.getId());

        try (var connection = DriverManager.getConnection(jdbcUrl, target.getUsername(), password);
             var statement = connection.createStatement()) {
            for (String sourceTable : sourceTables) {
                String resolved = tableMap.getOrDefault(sourceTable, sourceTable).toLowerCase(Locale.ROOT);
                String targetTable = IdentifierSanitizer.requireValid(resolved, "targetTable");
                if ("DROP_RECREATE".equals(mode)) {
                    statement.execute("DROP TABLE IF EXISTS `" + database + "`.`" + targetTable + "`");
                } else {
                    statement.execute("TRUNCATE TABLE `" + database + "`.`" + targetTable + "`");
                }
            }
            return sourceTables.size();
        } catch (Exception e) {
            throw new IllegalStateException("重采清理目标表失败: " + e.getMessage(), e);
        }
    }
}

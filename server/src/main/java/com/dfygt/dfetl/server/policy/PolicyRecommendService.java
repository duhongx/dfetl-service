package com.dfygt.dfetl.server.policy;

import org.springframework.stereotype.Service;

/**
 * spec 054 - 同步策略与校验联动推荐引擎（纯函数）。
 *
 * <p>设计文档 §15 决策伪代码 + §12 矩阵的 Java 实现。
 * 输入相同时输出必相同，便于单元测试。
 */
@Service
public class PolicyRecommendService {

    public PolicyRecommendOutput recommend(PolicyRecommendInput in) {
        if (in == null) throw new IllegalArgumentException("recommend input 不能为空");
        PolicyRecommendOutput out = new PolicyRecommendOutput();

        String mode = upper(in.getSyncMode(), "FULL_INCREMENT");
        String sourceKind = upper(in.getSourceKind(), "TABLE");
        boolean hasPk = Boolean.TRUE.equals(in.getHasPrimaryKey());
        boolean willUpdate = Boolean.TRUE.equals(in.getWillUpdateExisting());
        boolean physDelete = Boolean.TRUE.equals(in.getHasPhysicalDelete());
        boolean hasDelFlag = Boolean.TRUE.equals(in.getHasDeleteFlag());
        boolean updateTimeReliable = Boolean.TRUE.equals(in.getUpdateTimeReliable());
        boolean isView = "VIEW".equals(sourceKind) || "MATERIALIZED_VIEW".equals(sourceKind);
        String dataScale = upper(in.getDataScale(), "MEDIUM"); // SMALL/MEDIUM/LARGE
        String sensitivity = upper(in.getSensitivity(), "MEDIUM"); // LOW/MEDIUM/HIGH
        // spec 063 Task 10：insertWritesIncrField=false 表示 INSERT 不写增量字段（视图源常见），
        // 此时基于「增量字段窗口」的 WINDOW 校验会漏掉新插入行，必须降级为 FULL
        boolean incrFieldUnreliable = Boolean.FALSE.equals(in.getInsertWritesIncrField());

        // ── 1. Doris 表模型 ────────────────────────────────────────────────
        if (!hasPk) {
            out.setDorisTableModel("DUPLICATE_KEY");
            out.getReasons().add("无主键 → DUPLICATE KEY（追加写入，不去重）");
        } else if (willUpdate) {
            out.setDorisTableModel("UNIQUE_KEY");
            out.getReasons().add("有主键且会更新 → UNIQUE KEY（同主键覆盖）");
        } else {
            out.setDorisTableModel("UNIQUE_KEY");
            out.getReasons().add("有主键、不更新 → UNIQUE KEY（防止重复）");
        }

        // ── 2. 写入模式 ────────────────────────────────────────────────────
        if ("FULL".equals(mode)) {
            out.setWriteMode("TRUNCATE");
            out.getReasons().add("全量同步 → TRUNCATE 写入（清空目标后导入）");
        } else if (!hasPk) {
            out.setWriteMode("APPEND");
            out.getReasons().add("无主键 → APPEND 追加写入");
        } else if (willUpdate || hasDelFlag) {
            out.setWriteMode("UPSERT");
            out.getReasons().add("有主键 + 会更新/带删除标记 → UPSERT");
        } else {
            out.setWriteMode("APPEND");
            out.getReasons().add("有主键、纯新增 → APPEND（速度快）");
        }

        // ── 3. 校验方法 + 范围 ─────────────────────────────────────────────
        if (!hasPk) {
            out.setValidationMethod("ROW_COUNT");
            out.setValidationScope("FULL");
            out.getReasons().add("无主键 → ROW_COUNT 行数校验（无法做 hash 对账）");
        } else if ("FULL".equals(mode)) {
            out.setValidationMethod("CHECKSUM");
            out.setValidationScope("FULL");
            out.getReasons().add("全量 → CHECKSUM 全表 hash 对账（精确）");
        } else if (updateTimeReliable && !isView && !incrFieldUnreliable) {
            out.setValidationMethod("CHECKSUM");
            out.setValidationScope("WINDOW");
            out.getReasons().add("增量字段可靠 → WINDOW CHECKSUM（仅核对最近窗口，性能好）");
        } else {
            // spec 063 Task 10：HIGH 敏感度任务即使是视图/不可靠增量字段也优先用 CHECKSUM 全表
            if ("HIGH".equals(sensitivity) && hasPk) {
                out.setValidationMethod("CHECKSUM");
                out.setValidationScope("FULL");
                out.getReasons().add("数据敏感度=HIGH → 强制使用 CHECKSUM 全表校验，覆盖视图/不可靠增量字段场景");
            } else {
                out.setValidationMethod("ROW_COUNT");
                out.setValidationScope("FULL");
                out.getReasons().add(incrFieldUnreliable
                        ? "INSERT 不写增量字段 → WINDOW 无法捕捉新插入行，降级为 ROW_COUNT 全表"
                        : (isView ? "视图源 → ROW_COUNT 保守校验" : "增量字段不可靠 → ROW_COUNT 兜底"));
            }
        }

        // ── 4. autoTrigger / autoRepair ────────────────────────────────────
        out.setAutoTrigger(true);
        // autoRepair：默认开，但下面的硬规则会关
        boolean canAutoRepair = true;
        if ("ROW_COUNT".equals(out.getValidationMethod())) {
            canAutoRepair = false;
            out.getReasons().add("ROW_COUNT 校验不能直接 autoRepair（无法定位差异行）");
        }
        if (isView) {
            canAutoRepair = false;
            out.getWarnings().add("视图源默认关闭 autoRepair，手动确认后才能开启");
        }
        if ("DUPLICATE_KEY".equals(out.getDorisTableModel())) {
            canAutoRepair = false;
            out.getReasons().add("DUPLICATE KEY 模型 autoRepair 会重复插入，已关闭");
        }
        out.setAutoRepair(canAutoRepair);
        out.setAutoRepairMaxRows(canAutoRepair ? 1000L : null);
        // spec 063 Task 10：sensitivity 影响 autoRepair 行数上限（HIGH 收紧为 100，LOW 放宽到 5000）
        if (canAutoRepair) {
            if ("HIGH".equals(sensitivity)) {
                out.setAutoRepairMaxRows(100L);
                out.getReasons().add("数据敏感度=HIGH → autoRepair 行数上限收紧为 100");
            } else if ("LOW".equals(sensitivity)) {
                out.setAutoRepairMaxRows(5000L);
                out.getReasons().add("数据敏感度=LOW → autoRepair 行数上限放宽为 5000");
            }
        }
        // spec 063 Task 10：dataScale=LARGE 时即使条件满足也建议关闭 autoRepair（行数失控）
        if (canAutoRepair && "LARGE".equals(dataScale)) {
            out.setAutoRepair(false);
            out.setAutoRepairMaxRows(null);
            out.getWarnings().add("数据规模=LARGE → 已关闭 autoRepair，避免修复行数失控；如需开启请人工评估");
        }

        // ── 5. 快照对账 / 删除处理 ─────────────────────────────────────────
        if (physDelete && hasPk && !isView) {
            out.setSnapshotEnabled(true);
            out.setSnapshotDeleteMode(hasDelFlag ? "SOFT_DELETE" : "HARD_DELETE");
            out.getReasons().add("源端会物理删除 → 启用快照对账，模式=" + out.getSnapshotDeleteMode());
            if ("HARD_DELETE".equals(out.getSnapshotDeleteMode())) {
                out.getWarnings().add("HARD_DELETE 会真实删除目标行，请确认快照阈值与最大删除比例");
            }
        } else {
            out.setSnapshotEnabled(false);
            out.setSnapshotDeleteMode("NONE");
        }

        // ── 6. Drift-Watch ─────────────────────────────────────────────────
        boolean drift = "INCREMENT_ONLY".equals(mode) || "FULL_INCREMENT".equals(mode);
        out.setDriftWatchEnabled(drift && hasPk && !isView);
        if (out.getDriftWatchEnabled()) {
            out.getReasons().add("增量任务 + 主键 → 启用 Drift-Watch 周期巡检");
        }

        // ── 7. Lookback ────────────────────────────────────────────────────
        if ("WINDOW".equals(out.getValidationScope())) {
            int lb = isView ? 3600 : (updateTimeReliable ? 600 : 1800);
            // spec 063 Task 10：sensitivity=HIGH 时 lookback 加倍，LARGE 数据量加 50% 兜底乱序事件
            if ("HIGH".equals(sensitivity)) lb *= 2;
            if ("LARGE".equals(dataScale)) lb = (int) Math.round(lb * 1.5);
            out.setLookbackSeconds(lb);
            out.getReasons().add("WINDOW 校验默认 lookback=" + lb + "s（已结合 sensitivity / dataScale 调整）");
        }

        // ── 8. AGGREGATE 模型 vs CHECKSUM 校验冲突 ──────────────────────────
        if ("AGGREGATE_KEY".equals(out.getDorisTableModel()) && "CHECKSUM".equals(out.getValidationMethod())) {
            out.setValidationMethod("ROW_COUNT");
            out.getWarnings().add("AGGREGATE KEY 与 CHECKSUM 不兼容，已降级为 ROW_COUNT");
        }

        return out;
    }

    private String upper(String s, String def) {
        if (s == null || s.isBlank()) return def;
        return s.toUpperCase();
    }
}

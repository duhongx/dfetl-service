package com.dfygt.dfetl.server.service.publish;

import java.util.List;

/** 批量消息发布的逐条终态汇总。 */
public record PublishBatchResult(List<PublishOutcome> outcomes) {

    public PublishBatchResult {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public static PublishBatchResult empty() {
        return new PublishBatchResult(List.of());
    }

    public static PublishBatchResult allSent(int count) {
        if (count <= 0) {
            return empty();
        }
        return new PublishBatchResult(java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> PublishOutcome.sent(null))
                .toList());
    }

    public int sentCount() {
        return (int) outcomes.stream()
                .filter(outcome -> outcome.status() == PublishOutcome.Status.SENT)
                .count();
    }

    public int failedCount() {
        return outcomes.size() - sentCount();
    }
}

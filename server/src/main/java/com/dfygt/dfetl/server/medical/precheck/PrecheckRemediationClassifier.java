package com.dfygt.dfetl.server.medical.precheck;

import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/** 对相邻两次预检的问题键做稳定、可审计的整改状态分类。 */
public final class PrecheckRemediationClassifier {

    private PrecheckRemediationClassifier() {
    }

    public static Result classify(Set<String> previous, Set<String> current) {
        Set<String> oldKeys = previous == null ? Set.of() : previous;
        Set<String> newKeys = current == null ? Set.of() : current;
        TreeSet<String> stillOpen = new TreeSet<>(newKeys);
        stillOpen.retainAll(oldKeys);
        TreeSet<String> added = new TreeSet<>(newKeys);
        added.removeAll(oldKeys);
        TreeSet<String> fixed = new TreeSet<>(oldKeys);
        fixed.removeAll(newKeys);
        return new Result(
                Collections.unmodifiableSet(stillOpen),
                Collections.unmodifiableSet(added),
                Collections.unmodifiableSet(fixed));
    }

    public record Result(Set<String> stillOpen, Set<String> added, Set<String> fixed) {
    }
}

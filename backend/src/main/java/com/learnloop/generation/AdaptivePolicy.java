package com.learnloop.generation;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import static com.learnloop.generation.AdaptiveMapper.*;

/** Knowledge groups use the same fixed BKT parameters as bkt-fixed-v1. Replay keeps historical answers usable. */
final class AdaptivePolicy {
    static final String VERSION = "group-bkt-fixed-v1";
    private static final double WEAK_BELOW = 0.60;
    private AdaptivePolicy() {}

    record Candidate(Topic topic, long block, Previous previous, int questionCount) {
        Candidate(Topic topic, long block, Previous previous) { this(topic, block, previous, previous == null ? 0 : 1); }
    }
    record Target(Candidate candidate, String reason) {}
    record GroupState(Double probability, int evidenceCount, int topicCount) {}
    record Plan(List<Target> targets, String notice) {}

    static String groupKey(Topic topic) { return normalize(topic.chapter()) + "\u001f" + normalize(topic.groupName()); }
    static String topicKey(Topic topic) { return groupKey(topic) + "\u001f" + normalize(topic.name()); }
    static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ");
    }

    static Map<String, GroupState> mastery(List<Topic> topics, List<Observation> observations) {
        Map<String, String> keys = new HashMap<>();
        topics.forEach(topic -> keys.put(topic.id(), groupKey(topic)));
        Map<String, GroupState> result = new HashMap<>();
        Map<String, Evidence> groups = new HashMap<>();
        for (Observation event : observations) {
            String key = keys.get(event.topicId());
            if (key != null) groups.computeIfAbsent(key, ignored -> new Evidence()).observe(event);
        }
        groups.forEach((key, state) -> result.put(key, new GroupState(
                state.families.size() >= 5 && state.topics.size() >= 2 ? state.probability : null,
                state.families.size(), state.topics.size())));
        return result;
    }

    private static final class Evidence {
        double probability = .2;
        final Set<String> families = new HashSet<>();
        final Set<String> topics = new HashSet<>();
        void observe(Observation event) {
            // 观测已通过答题服务的当日题族去重。跨日变式可以更新概率，但不增加独立题族数。
            families.add(event.familyId());
            topics.add(event.topicId());
            double mastered = probability * (event.correct() ? .9 : .1);
            double unmastered = (1 - probability) * (event.correct() ? .25 : .75);
            double posterior = mastered / (mastered + unmastered);
            probability = posterior + (1 - posterior) * .1;
        }
    }

    static Plan select(List<Candidate> candidates, Map<String, GroupState> groups, List<Observation> observations,
                       boolean focus, int count, LocalDateTime now) {
        Map<String, Observation> latest = new HashMap<>();
        for (Observation event : observations) latest.put(event.topicId(), event);
        List<Target> selected = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Map<String, Integer> groupCounts = new HashMap<>();
        String notice = null;
        if (focus) {
            List<Candidate> weak = candidates.stream()
                    .filter(c -> {
                        GroupState state = groups.get(groupKey(c.topic()));
                        return state != null && state.probability() != null && state.probability() < WEAK_BELOW;
                    })
                    .filter(c -> {
                        Observation last = latest.get(c.topic().id());
                        return recentError(last, now) || c.previous() == null;
                    })
                    .sorted(Comparator.comparingInt((Candidate c) -> recentError(latest.get(c.topic().id()), now) ? 0 : 1)
                            .thenComparingDouble(c -> groups.get(groupKey(c.topic())).probability())
                            .thenComparing(c -> c.topic().id())).toList();
            for (Candidate candidate : weak) {
                if (selected.size() >= count * 7 / 10) break;
                select(candidate, candidate.previous() == null ? "WEAK_GROUP_NEW" : "RECENT_ERROR_VARIANT",
                        selected, used, groupCounts);
            }
            if (selected.isEmpty()) {
                boolean enough = candidates.stream().map(c -> groups.get(groupKey(c.topic())))
                        .anyMatch(state -> state != null && state.probability() != null);
                notice = enough ? "暂无可强化考点，按覆盖出题" : "样本不足，按覆盖出题";
            }
        }
        List<Candidate> remaining = new ArrayList<>(candidates.stream()
                .filter(c -> c.previous() == null && !used.contains(c.topic().id())).toList());
        Comparator<Candidate> coverageOrder = Comparator
                .comparingInt((Candidate c) -> groupCounts.getOrDefault(groupKey(c.topic()), 0))
                .thenComparingInt(Candidate::questionCount)
                .thenComparingLong(Candidate::block).thenComparing(c -> c.topic().id());
        while (selected.size() < count && !remaining.isEmpty()) {
            Candidate candidate = Collections.min(remaining, coverageOrder);
            remaining.remove(candidate);
            select(candidate, "COVERAGE", selected, used, groupCounts);
        }
        if (selected.size() < count) {
            String shortfall = "新考点不足";
            notice = notice == null ? shortfall : notice + " · " + shortfall;
        }
        return new Plan(List.copyOf(selected), notice);
    }

    private static boolean recentError(Observation last, LocalDateTime now) {
        return last != null && !last.correct() && !last.createdAt().isBefore(now.minusDays(30));
    }

    private static void select(Candidate candidate, String reason, List<Target> selected, Set<String> used,
                               Map<String, Integer> groupCounts) {
        if (!used.add(candidate.topic().id())) return;
        selected.add(new Target(candidate, reason));
        groupCounts.merge(groupKey(candidate.topic()), 1, Integer::sum);
    }
}

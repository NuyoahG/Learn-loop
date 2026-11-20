package com.learnloop.generation;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.learnloop.generation.AdaptiveMapper.*;
import static com.learnloop.generation.AdaptivePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptivePolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final Topic LOCAL = topic("1", "均衡化");
    private static final Topic GLOBAL = topic("2", "均衡化");
    private static final Topic TARGET = topic("3", "规定化");
    private static final Topic MAPPING = topic("4", "规定化");
    private static final Topic NEW = topic("5", "覆盖");
    private static final List<Topic> TOPICS = List.of(LOCAL, GLOBAL, TARGET, MAPPING, NEW);
    private static final List<Candidate> CANDIDATES = TOPICS.stream().map(topic -> new Candidate(topic, 1,
            topic == NEW ? null : new Previous(topic.id(), "previous", "旧题", null))).toList();

    @Test
    void sameScopeSelectsDifferentGroupsFromIndependentEvidence() {
        var equalizationWeak = history(false);
        var specificationWeak = history(true);
        assertEquals(LOCAL.id(), select(CANDIDATES, mastery(TOPICS, equalizationWeak), equalizationWeak, true, 3, NOW)
                .targets().getFirst().candidate().topic().id());
        assertEquals(TARGET.id(), select(CANDIDATES, mastery(TOPICS, specificationWeak), specificationWeak, true, 3, NOW)
                .targets().getFirst().candidate().topic().id());
        assertEquals(List.of("5"), select(CANDIDATES, mastery(TOPICS, equalizationWeak), equalizationWeak, false, 3, NOW)
                .targets().stream().map(target -> target.candidate().topic().id()).toList());
    }

    @Test
    void severalVariantsInSameFamilyDoNotMeetIndependentEvidenceThreshold() {
        List<Observation> repeated = new ArrayList<>();
        for (int day = 7; day >= 1; day--) repeated.add(new Observation(day % 2 == 0 ? "1" : "2",
                "same-family", false, NOW.minusDays(day)));
        var groups = mastery(TOPICS, repeated);
        assertNull(groups.get(groupKey(LOCAL)).probability());
        assertTrue(select(CANDIDATES, groups, repeated, true, 3, NOW).notice().startsWith("样本不足"));
    }

    private static Topic topic(String id, String group) { return new Topic(id, "直方图处理", group, "小考点 " + id, "定义"); }

    private static List<Observation> history(boolean firstGroupCorrect) {
        List<Observation> history = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            history.add(new Observation(index == 0 ? GLOBAL.id() : LOCAL.id(), "first-" + index,
                    firstGroupCorrect, NOW.minusDays(6 - index)));
            history.add(new Observation(index == 0 ? MAPPING.id() : TARGET.id(), "second-" + index,
                    !firstGroupCorrect, NOW.minusDays(6 - index)));
        }
        return history;
    }
}

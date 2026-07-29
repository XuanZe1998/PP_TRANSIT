package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRoutePlannerTests {

    private final ChannelRoutePlanner planner = new ChannelRoutePlanner();

    @Test
    void neverLetsWeightOverrideAPriorityTier() {
        ChannelRoutePlanner.Candidate high = candidate(1, 100, 1, 1);
        ChannelRoutePlanner.Candidate low = candidate(2, 90, 10_000, 100);

        List<ChannelRoutePlanner.Candidate> ordered = planner.plan(
                List.of(low, high), new SplittableRandom(7));

        assertThat(ordered).containsExactly(high, low);
    }

    @Test
    void usesChannelWeightAndTrafficPercentInsideOneTier() {
        ChannelRoutePlanner.Candidate heavy = candidate(1, 100, 100, 100);
        ChannelRoutePlanner.Candidate light = candidate(2, 100, 1, 1);
        int heavyFirst = 0;

        for (int seed = 0; seed < 200; seed++) {
            List<ChannelRoutePlanner.Candidate> ordered = planner.plan(
                    List.of(light, heavy), new SplittableRandom(seed));
            if (ordered.get(0) == heavy) heavyFirst++;
        }

        assertThat(heavyFirst).isGreaterThan(190);
        assertThat(planner.effectiveWeight(heavy.mapping(), heavy.channel())).isEqualTo(10_000);
    }

    private ChannelRoutePlanner.Candidate candidate(long id, int priority, int weight, int traffic) {
        Channel channel = Channel.builder().id(id).weight(weight).build();
        ModelMapping mapping = ModelMapping.builder()
                .id(id)
                .priority(priority)
                .trafficPercent(traffic)
                .build();
        return new ChannelRoutePlanner.Candidate(mapping, channel);
    }
}

package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

/**
 * Orders candidate routes by strict priority tiers and performs weighted random
 * selection without replacement inside each tier. This preserves deterministic
 * failover semantics while allowing equal-priority channels to share traffic.
 */
@Component
public class ChannelRoutePlanner {

    public List<Candidate> plan(List<Candidate> candidates) {
        return plan(candidates, ThreadLocalRandom.current());
    }

    List<Candidate> plan(List<Candidate> candidates, RandomGenerator random) {
        Map<Integer, List<Candidate>> tiers = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparingInt((Candidate item) -> item.mapping().getPriority()).reversed())
                .forEach(candidate -> tiers.computeIfAbsent(candidate.mapping().getPriority(), ignored -> new ArrayList<>())
                        .add(candidate));

        List<Candidate> result = new ArrayList<>(candidates.size());
        tiers.values().forEach(tier -> result.addAll(weightedWithoutReplacement(
                tier,
                candidate -> effectiveWeight(candidate.mapping(), candidate.channel()),
                random)));
        return List.copyOf(result);
    }

    private <T> List<T> weightedWithoutReplacement(List<T> values, ToLongFunction<T> weight,
                                                    RandomGenerator random) {
        List<T> remaining = new ArrayList<>(values);
        List<T> ordered = new ArrayList<>(values.size());
        while (!remaining.isEmpty()) {
            long total = remaining.stream().mapToLong(weight).map(value -> Math.max(1L, value)).sum();
            long draw = random.nextLong(total);
            long cursor = 0;
            int selected = remaining.size() - 1;
            for (int index = 0; index < remaining.size(); index++) {
                cursor += Math.max(1L, weight.applyAsLong(remaining.get(index)));
                if (draw < cursor) {
                    selected = index;
                    break;
                }
            }
            ordered.add(remaining.remove(selected));
        }
        return ordered;
    }

    long effectiveWeight(ModelMapping mapping, Channel channel) {
        long channelWeight = Math.max(1, channel.getWeight());
        long trafficPercent = Math.max(1, mapping.getTrafficPercent());
        return Math.multiplyExact(channelWeight, trafficPercent);
    }

    public record Candidate(ModelMapping mapping, Channel channel) {
    }
}

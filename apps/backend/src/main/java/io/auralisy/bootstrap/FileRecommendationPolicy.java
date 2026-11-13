package io.auralisy.bootstrap;

import static io.auralisy.contracts.CanonicalJson.JSON;

import io.auralisy.budget.api.BudgetAuthority;
import io.auralisy.discovery.api.RecommendationPolicy;
import io.auralisy.persistence.PersistenceRejected;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class FileRecommendationPolicy implements RecommendationPolicy {
    private final String snapshot;

    public FileRecommendationPolicy(
            @Value("${auralis.recommendation-config:}") String path,
            WireProtocol schema,
            OnlineProperties properties) {
        if (path.isBlank()) {
            snapshot = null;
            return;
        }
        try {
            var file = Path.of(path);
            if (Files.size(file) > 4 * 1024 * 1024) throw new IllegalArgumentException();
            var n = JSON.readTree(Files.readAllBytes(file));
            var fields = new HashSet<String>();
            n.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(
                    Set.of(
                            "agent_policy",
                            "selection_config",
                            "model_bindings",
                            "recall_binding",
                            "budget_limits",
                            "prices",
                            "web_guards",
                            "capabilities",
                            "reference_revision",
                            "parser_revisions",
                            "reference_genres",
                            "audience_binding",
                            "idle_close_ms",
                            "stage_capacity"))) throw new IllegalArgumentException();
            var agentPolicy =
                    (com.fasterxml.jackson.databind.node.ObjectNode) n.path("agent_policy");
            if (!agentPolicy.has("audience_min_listeners"))
                agentPolicy.put("audience_min_listeners", schema.defaultAudienceMinimum());
            if (!agentPolicy.has("audience_binding")) {
                var binding = n.path("audience_binding");
                agentPolicy
                        .putObject("audience_binding")
                        .set("dataset_id", binding.path("dataset"));
                var target =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                agentPolicy.path("audience_binding");
                target.set("window", binding.path("window"));
                target.set("sample_listeners", binding.path("sampleListeners"));
                target.set("snapshot_digest", binding.path("snapshotDigest"));
            }
            // The previous online default only allowed search or read before the final answer.
            var writing = agentPolicy.path("writing");
            if (writing.path("long_tool_turns").isIntegralNumber()
                    && writing.path("long_tool_turns").asInt() == 1)
                ((com.fasterxml.jackson.databind.node.ObjectNode) writing)
                        .put("long_tool_turns", 2);
            agentPolicy.put(
                    "digest",
                    io.auralisy.contracts.CanonicalJson.contentDigest(agentPolicy, "digest"));
            for (var pair :
                    Map.of(
                                    "agent_policy",
                                    "AgentPolicy",
                                    "selection_config",
                                    "SelectionConfig",
                                    "recall_binding",
                                    "RecallBinding")
                            .entrySet())
                if (!schema.validDefinition(pair.getValue(), n.path(pair.getKey())))
                    throw new IllegalArgumentException();
            if (agentPolicy.path("audience_max_listeners").asLong(Long.MAX_VALUE)
                    < agentPolicy.path("audience_min_listeners").asInt())
                throw new IllegalArgumentException();
            if (n.path("reference_revision").asText().isBlank()
                    || n.path("parser_revisions").isEmpty()
                    || !n.path("reference_genres").isObject()
                    || n.path("idle_close_ms").asInt() < 1) throw new IllegalArgumentException();
            var limits = n.path("budget_limits");
            long session = limits.path("session_cap_microusd").asLong(-1),
                    round = limits.path("round_cap_microusd").asLong(-1);
            if (round < 0
                    || session < round
                    || session > Integer.MAX_VALUE
                    || limits.path("lane_caps").path("FOREGROUND").asLong(-1) < 0
                    || limits.path("lane_caps").path("BACKGROUND").asLong(-1) < 0
                    || limits.path("lane_caps").path("FOREGROUND").asLong()
                                    + limits.path("lane_caps").path("BACKGROUND").asLong()
                            > round) throw new IllegalArgumentException();
            for (String role : List.of("interpret", "screen", "select", "write"))
                if (!n.path("model_bindings").has(role)) throw new IllegalArgumentException();
            for (var binding : n.path("model_bindings")) {
                if (!schema.validDefinition("ModelBinding", binding))
                    throw new IllegalArgumentException();
                var price =
                        JSON.treeToValue(
                                n.path("prices").path(binding.path("ref").asText()),
                                BudgetAuthority.Price.class);
                if (price == null
                        || price.input() < 0
                        || price.cachedInput() < 0
                        || price.cachedInput() > price.input()
                        || price.output() < 0
                        || price.search() < 0
                        || !price.revision().equals(binding.path("price_revision").asText())
                        || price.maxInput() != binding.path("max_input_tokens").asLong()
                        || price.maxOutput() != binding.path("max_output_tokens").asLong())
                    throw new IllegalArgumentException();
            }
            var select = n.path("selection_config");
            var capacity = n.path("stage_capacity");
            long r = select.path("recall_target_count").asLong(),
                    k = select.path("shortlist_target_count").asLong(),
                    count = select.path("recommendation_target_count").asLong();
            if (r < k || k < count) throw new IllegalArgumentException();
            for (String name :
                    List.of(
                            "recall_target_count",
                            "shortlist_target_count",
                            "recommendation_target_count"))
                if (!capacity.path(name).isIntegralNumber()
                        || capacity.path(name).asLong() < select.path(name).asLong())
                    throw new IllegalArgumentException();
            var check =
                    JSON.createObjectNode()
                            .put("session_budget_scope_id", "configuration-session")
                            .put("round_budget_scope_id", "configuration-round");
            check.set("limits", limits);
            check.putObject("remaining_at_issue")
                    .put("session", 0)
                    .put("round", 0)
                    .put("FOREGROUND", 0)
                    .put("BACKGROUND", 0);
            check.putObject("structure_repair")
                    .put("foreground_per_run", 1)
                    .put("background_per_round", 1);
            if (!schema.validDefinition("Budget", check)) throw new IllegalArgumentException();
            properties.setRecommendationTargetCount((int) count);
            snapshot = n.toString();
        } catch (Exception failure) {
            throw new IllegalArgumentException("INVALID_RECOMMENDATION_CONFIGURATION");
        }
    }

    public String snapshot() {
        if (snapshot == null) throw new PersistenceRejected("RECOMMENDATION_UNAVAILABLE");
        return snapshot;
    }
}

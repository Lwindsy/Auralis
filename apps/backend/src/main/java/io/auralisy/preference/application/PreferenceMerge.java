package io.auralisy.preference.application;

import static io.auralisy.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.auralisy.contracts.MessageDefinitions;
import io.auralisy.preference.api.PreferenceRejected;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic counterpart of preferences.resolution.merge_preferences; no model or I/O. */
@Component
public final class PreferenceMerge {
    private final MessageDefinitions definitions;

    public PreferenceMerge(MessageDefinitions definitions) {
        this.definitions = definitions;
    }

    public void require(String name, JsonNode value) {
        if (!definitions.validDefinition(name, value))
            throw new PreferenceRejected("INVALID_PAYLOAD");
    }

    public ObjectNode merge(
            JsonNode raw,
            JsonNode proposal,
            String revision,
            Map<String, String> names,
            Map<String, String> seedDescriptions) {
        require("RawInput", raw);
        require("PreferenceResolutionProposal", proposal);
        if (!contentDigest(proposal, "proposal_digest")
                .equals(proposal.path("proposal_digest").asText()))
            throw new PreferenceRejected("BINDING_MISMATCH");
        for (String key : List.of("preferences", "constraints"))
            for (var row : proposal.path(key))
                if (row.path("text_span").path("start").asInt()
                        >= row.path("text_span").path("end").asInt())
                    throw new PreferenceRejected("INVALID_TEXT_SPAN");
        if (!contentDigest(raw, "input_digest").equals(raw.path("input_digest").asText())
                || !raw.path("input_digest").equals(proposal.path("input_digest"))
                || !revision.equals(proposal.path("reference_revision").asText()))
            throw new PreferenceRejected("PREFERENCE_BINDING_MISMATCH");
        String text = raw.path("request_text").asText();
        for (var span : proposal.path("unresolved_constraints")) span(text, span);
        if (!proposal.path("unresolved_constraints").isEmpty())
            throw new PreferenceRejected("UNSUPPORTED_CONSTRAINT", "NEEDS_INPUT");
        var tags = new LinkedHashMap<String, ObjectNode>();
        var disliked = strings(raw.path("disliked_genre_ids"));
        for (var row : raw.path("liked_genres")) {
            String id = row.path("genre_id").asText();
            if (tags.putIfAbsent(id, weighted(id, row)) != null)
                throw new PreferenceRejected("DUPLICATE_ID");
        }
        var selected = new HashSet<>(tags.keySet());
        selected.addAll(disliked);
        var seeds = new LinkedHashMap<String, ObjectNode>();
        var soft = JSON.createArrayNode();
        var overrides = JSON.createArrayNode();
        var required = new LinkedHashSet<String>();
        var forbidden = new LinkedHashSet<String>();
        var directions = new HashMap<String, Boolean>();
        var rows = new ArrayList<JsonNode>();
        proposal.path("preferences").forEach(rows::add);
        proposal.path("constraints").forEach(rows::add);
        var albumIds = new HashSet<String>();
        for (var album : raw.path("liked_albums")) {
            String id =
                    album.path("release_group_id").isNull()
                            ? album.path("external").path("mb_release_group_id").asText()
                            : album.path("release_group_id").asText();
            if (!albumIds.add(id)) throw new PreferenceRejected("DUPLICATE_ID");
            String description = seedDescriptions.get(id);
            if (description == null || description.isBlank())
                throw new PreferenceRejected("INPUT_DESCRIPTION_UNAVAILABLE");
            addSoft(
                    soft,
                    "seed:" + soft.size(),
                    "喜欢专辑：" + description,
                    "prefer",
                    "explicit",
                    album.path("strength").asText(),
                    album.path("focused").asBoolean());
            if (!album.path("release_group_id").isNull()) {
                if (seeds.putIfAbsent(id, weighted(id, album)) != null)
                    throw new PreferenceRejected("DUPLICATE_ID");
            } else
                for (var genre : album.path("external").path("mapped_genre_ids")) {
                    name(names, genre.asText());
                    if (!disliked.contains(genre.asText()))
                        tags.putIfAbsent(genre.asText(), weighted(genre.asText(), album));
                }
        }
        for (var row : rows) {
            String id = row.path("id").asText(), kind = row.path("kind").asText();
            name(names, id);
            span(text, row.path("text_span"));
            if (row.path("basis").asText("explicit").equals("explicit")) {
                boolean positive = kind.equals("prefer") || kind.equals("require");
                if (directions.containsKey(id) && directions.get(id) != positive)
                    throw new PreferenceRejected("PREFERENCE_CONFLICT", "NEEDS_INPUT");
                directions.put(id, positive);
            }
        }
        var explicit = new HashSet<String>();
        for (var row : rows)
            if (row.path("basis").asText("explicit").equals("explicit"))
                explicit.add(row.path("id").asText());
        for (var row : rows) {
            String id = row.path("id").asText(),
                    kind = row.path("kind").asText(),
                    basis = row.path("basis").asText("explicit");
            if (basis.equals("inferred") && (selected.contains(id) || explicit.contains(id)))
                continue;
            if (basis.equals("explicit"))
                overrides
                        .addObject()
                        .put("genre_id", id)
                        .put("action", kind.toUpperCase(Locale.ROOT))
                        .set("text_span", row.path("text_span").deepCopy());
            if (kind.equals("prefer") || kind.equals("require")) {
                disliked.remove(id);
                // Reaffirmation preserves independent strength/focus flags, per user decision.
                tags.putIfAbsent(
                        id,
                        JSON.createObjectNode()
                                .put("id", id)
                                .put("strength", "normal")
                                .put("focused", false));
                if (kind.equals("require")) required.add(id);
            } else {
                tags.remove(id);
                if (kind.equals("exclude")) forbidden.add(id);
                else
                    addSoft(
                            soft,
                            "genre-avoid:" + soft.size(),
                            "倾向避开风格：" + name(names, id),
                            "avoid",
                            basis,
                            "normal",
                            false);
            }
        }
        tags.forEach(
                (id, row) -> {
                    boolean inferred = false;
                    for (var evidence : proposal.path("preferences"))
                        if (evidence.path("id").asText().equals(id)
                                && evidence.path("basis").asText().equals("inferred"))
                            inferred = true;
                    String basis =
                            inferred && !selected.contains(id) && !explicit.contains(id)
                                    ? "inferred"
                                    : "explicit";
                    addSoft(
                            soft,
                            "genre:" + soft.size(),
                            "喜欢风格：" + name(names, id),
                            "prefer",
                            basis,
                            row.path("strength").asText(),
                            row.path("focused").asBoolean());
                });
        for (String id : disliked)
            addSoft(
                    soft,
                    "disliked:" + soft.size(),
                    "预选不喜欢风格：" + name(names, id),
                    "avoid",
                    "explicit",
                    "normal",
                    false);
        for (String id : required)
            addSoft(
                    soft,
                    "required:" + soft.size(),
                    "每张必须包含风格：" + name(names, id),
                    "context",
                    "explicit",
                    "normal",
                    false);
        for (String id : forbidden)
            addSoft(
                    soft,
                    "forbidden:" + soft.size(),
                    "排除含有风格的作品：" + name(names, id),
                    "context",
                    "explicit",
                    "normal",
                    false);
        var positiveRoots = new LinkedHashSet<String>();
        for (var row : rows)
            if (Set.of("prefer", "require").contains(row.path("kind").asText())
                    && row.path("basis").asText("explicit").equals("explicit"))
                positiveRoots.add(row.path("id").asText());
        if (!disliked.isEmpty() && !positiveRoots.isEmpty())
            addSoft(
                    soft,
                    "disliked-exceptions:" + soft.size(),
                    "若以下本次明确喜欢的风格属于预选不喜欢项的子类，则该风格及其子类作为例外："
                            + String.join(
                                    "、", positiveRoots.stream().map(id -> name(names, id)).toList())
                            + "。其他子类仍按原规则排除；独立的更具体预选和明确硬排除不因该例外放宽。",
                    "context",
                    "explicit",
                    "normal",
                    false);
        proposal.path("soft_preferences").forEach(row -> soft.add(row.deepCopy()));
        var result =
                JSON.createObjectNode()
                        .put(
                                "resolution_id",
                                "resolution:" + proposal.path("proposal_digest").asText())
                        .put("input_digest", raw.path("input_digest").asText())
                        .put("reference_revision", revision)
                        .put("parser_revision", proposal.path("parser_revision").asText());
        result.set("seeds", JSON.valueToTree(seeds.values()));
        result.set("tags", JSON.valueToTree(tags.values()));
        result.set("disliked_tags", JSON.valueToTree(disliked));
        result.set("forbidden_tags", JSON.valueToTree(forbidden));
        result.set("required_tags", JSON.valueToTree(required));
        result.set("soft_preferences", soft);
        result.set("overrides", overrides);
        result.put("mode", raw.path("mode").asText())
                .put("has_positive_signal", !seeds.isEmpty() || !tags.isEmpty());
        result.put(
                "effective_preference_digest",
                contentDigest(result, "effective_preference_digest"));
        require("EffectivePreferenceSnapshot", result);
        return result;
    }

    private static LinkedHashSet<String> strings(JsonNode array) {
        var result = new LinkedHashSet<String>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    private static String name(Map<String, String> names, String id) {
        String value = names.get(id);
        if (value == null || value.isBlank())
            throw new PreferenceRejected("INPUT_DESCRIPTION_UNAVAILABLE");
        return value;
    }

    private static ObjectNode weighted(String id, JsonNode row) {
        return JSON.createObjectNode()
                .put("id", id)
                .put("strength", row.path("strength").asText())
                .put("focused", row.path("focused").asBoolean());
    }

    private static void span(String text, JsonNode span) {
        int start = span.path("start").asInt(), end = span.path("end").asInt();
        if (start < 0 || start >= end || end > text.codePointCount(0, text.length()))
            throw new PreferenceRejected("INTERPRETATION_INVALID_SPAN");
    }

    private static void addSoft(
            ArrayNode soft,
            String id,
            String description,
            String kind,
            String basis,
            String strength,
            boolean focused) {
        soft.addObject()
                .put("preference_id", id)
                .put("description", description)
                .put("kind", kind)
                .put("basis", basis)
                .put("strength", strength)
                .put("focused", focused);
    }
}

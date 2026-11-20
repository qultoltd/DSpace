/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Mutative;

/**
 * Suggests 0–6 subject keywords for an item with Qwen3-4B-Instruct-2507 in LM Studio, as evaluated in the Qulto
 * keyword pilot (docs/llm-pilot in the installer repository, dspace-integration-spec.md). Suggestions go to a
 * separate, hidden field for a cataloguer to approve; {@code dc.subject} is never touched.
 *
 * <p>Configuration ({@code config/modules/suggestkeywords.cfg}), all keys prefixed with the task name
 * {@code suggestkeywords.}:
 * <pre>
 * dry-run            true (default): report only, write nothing
 * llm.url            http://localhost:1234/v1
 * llm.model          qwen3-4b-instruct-2507
 * llm.timeout-seconds 180
 * excerpt.budget-tokens 2800
 * max-keywords       6
 * languages          languages to process (the prompt and checks were evaluated for hu)
 * language.field     dc.language.iso
 * language.map       value:code pairs, e.g. hun:hu, magyar:hu
 * language.verify    true: also run the language check when metadata is present
 * kind.rules         field:value=kind, kind = fiction | literature | other
 * kind.collection.UUID = kind (only for homogeneous collections)
 * field.suggested    local.subject.suggested
 * field.run          local.description.keywordrun
 * </pre>
 *
 * <p>Status: SUCCESS (suggestions written or reported; zero is a valid result), SKIP (not an item, no extracted
 * text, fiction, unsupported language, too little text), FAIL (the model answered but not usably), ERROR (the model
 * could not be reached). On FAIL and ERROR nothing is written.
 */
@Mutative
public class SuggestKeywords extends AbstractCurationTask {

    private static final Logger log = LogManager.getLogger(SuggestKeywords.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MIN_CHARS = 200;

    private static volatile KeywordPipeline sharedPipeline;
    private static volatile String sharedKey;

    private BitstreamService bitstreamService;

    @Override
    public void init(Context ctx, Curator curator, String taskId) throws IOException {
        super.init(ctx, curator, taskId);
        bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    }

    /** The pipeline loads ~20 MB of models; it is built once per configuration and shared. */
    private KeywordPipeline pipeline() throws IOException {
        String url = prop("llm.url", "http://localhost:1234/v1");
        String model = prop("llm.model", "qwen3-4b-instruct-2507");
        int timeout = taskIntProperty("llm.timeout-seconds", 180);
        int budget = taskIntProperty("excerpt.budget-tokens", KeywordPipeline.DEFAULT_BUDGET);
        int max = taskIntProperty("max-keywords", 6);
        String key = url + "|" + model + "|" + timeout + "|" + budget + "|" + max;
        if (sharedPipeline == null || !key.equals(sharedKey)) {
            synchronized (SuggestKeywords.class) {
                if (sharedPipeline == null || !key.equals(sharedKey)) {
                    sharedPipeline = new KeywordPipeline(new LlmClient(url, model, Duration.ofSeconds(timeout)),
                        budget, max);
                    sharedKey = key;
                }
            }
        }
        return sharedPipeline;
    }

    private String prop(String name, String dflt) {
        String v = taskProperty(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    @Override
    public int perform(Context context, DSpaceObject dso) throws IOException {
        if (!(dso instanceof Item item)) {
            return skip("not an item");
        }
        String handle = item.getHandle() != null ? item.getHandle() : item.getID().toString();
        boolean dryRun = taskBooleanProperty("dry-run", true);
        long t0 = System.nanoTime();
        try {
            // 1. kind of document
            String kind = kind(context, item);
            if ("fiction".equals(kind)) {
                return skip(handle + ": fiction (kind mapping); no reliable topical keywords can be suggested");
            }
            // 2. text
            Source src = text(context, item);
            if (src == null) {
                return skip(handle + ": no extracted text (TEXT bundle); run the text extraction media filter first");
            }
            String title = itemService.getMetadataFirstValue(item, "dc", "title", null, Item.ANY);
            KeywordPipeline p = pipeline();
            TextCleaner.Result clean = p.clean(src.text(), title == null ? "" : title, src.pdf());
            if (PyText.len(clean.text()) < MIN_CHARS) {
                return skip(handle + ": too little text (" + PyText.len(clean.text()) + " characters)");
            }
            // 3. language
            Lang lang = language(item, clean, p);
            Set<String> allowed = new HashSet<>(arr("languages", "hu"));
            if (lang.code() == null || !allowed.contains(lang.code())) {
                return skip(handle + ": language " + lang.code() + " (" + lang.source()
                    + ") is not in suggestkeywords.languages " + allowed);
            }
            // 4. keywords
            KeywordPipeline.Outcome out = p.suggest(clean, lang.code(), "literature".equals(kind));
            double secs = (System.nanoTime() - t0) / 1e9;
            if (!out.ok()) {
                log.warn("suggestkeywords {}: model answer unusable: {}", handle, out.error());
                setResult(handle + ": FAIL, the model's answer was not usable (" + out.error() + "); nothing written");
                return Curator.CURATE_FAIL;
            }
            // 5. write (or report)
            List<String> skippedApproved = new ArrayList<>();
            List<String> skippedRejected = new ArrayList<>();
            List<KeywordChecks.Checked> toWrite = new ArrayList<>();
            Set<String> approved = lowerValues(item, "dc.subject");
            Set<String> rejected = rejectedByCataloguer(item);
            for (KeywordChecks.Checked k : out.keywords()) {
                String low = k.keyword().toLowerCase(Locale.ROOT);
                if (approved.contains(low)) {
                    skippedApproved.add(k.keyword());
                } else if (rejected.contains(low)) {
                    skippedRejected.add(k.keyword());
                } else {
                    toWrite.add(k);
                }
            }
            ObjectNode run = runRecord(out, lang, kind, src, toWrite, skippedApproved, dryRun, secs);
            ArrayNode rj = run.putArray("removed_by_cataloguer_before");
            skippedRejected.forEach(rj::add);
            if (!dryRun) {
                write(context, item, toWrite, run);
            }
            log.info("suggestkeywords {}: {} keywords, {} s, prompt {} tokens, excerpt {} tokens{}", handle,
                toWrite.size(), String.format(Locale.ROOT, "%.1f", secs), out.answer().promptTokens(),
                out.excerpt().tokens(), dryRun ? " (dry run)" : "");
            StringBuilder r = new StringBuilder(handle).append(": ").append(toWrite.size()).append(" suggestion(s)")
                .append(dryRun ? " (dry run, nothing written)" : "").append(", language ").append(lang.code())
                .append(" (").append(lang.source()).append(")");
            for (KeywordChecks.Checked k : toWrite) {
                r.append(" | ").append(k.keyword());
                if (!k.flags().isEmpty()) {
                    r.append(" ").append(k.flags());
                }
            }
            if (!skippedApproved.isEmpty()) {
                r.append(" | already in dc.subject: ").append(skippedApproved);
            }
            if (!skippedRejected.isEmpty()) {
                r.append(" | removed by a cataloguer before, not suggested again: ").append(skippedRejected);
            }
            setResult(r.toString());
            return Curator.CURATE_SUCCESS;
        } catch (LlmClient.LlmUnavailable e) {
            log.warn("suggestkeywords {}: {}", handle, e.getMessage());
            setResult(handle + ": ERROR, the model could not be reached (" + e.getMessage() + "); nothing written");
            return Curator.CURATE_ERROR;
        } catch (SQLException | AuthorizeException e) {
            log.error("suggestkeywords " + handle, e);
            setResult(handle + ": ERROR, " + e.getMessage() + "; nothing written");
            return Curator.CURATE_ERROR;
        }
    }

    private int skip(String why) {
        setResult(why);
        return Curator.CURATE_SKIP;
    }

    // ── kind of document ────────────────────────────────────────────────────────────────────────────────────

    /** fiction | literature | other | unknown, from the collection setting or the field rules. */
    private String kind(Context context, Item item) throws SQLException {
        if (item.getOwningCollection() != null) {
            String c = taskProperty("kind.collection." + item.getOwningCollection().getID());
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        for (String rule : arr("kind.rules", "")) {
            int colon = rule.indexOf(':');
            int eq = rule.lastIndexOf('=');
            if (colon < 0 || eq < colon) {
                continue;
            }
            String field = rule.substring(0, colon).trim();
            String value = rule.substring(colon + 1, eq).trim();
            String kind = rule.substring(eq + 1).trim();
            for (MetadataValue v : itemService.getMetadataByMetadataString(item, field)) {
                if (v.getValue() != null && v.getValue().trim().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
        }
        return "unknown";
    }

    // ── text ────────────────────────────────────────────────────────────────────────────────────────────────

    private record Source(String text, String name, boolean pdf) {
    }

    /**
     * The extracted text of the main file: the ORIGINAL bundle's primary bitstream if it has a TEXT derivative,
     * otherwise the original with the largest TEXT derivative ("name.ext" → "name.ext.txt").
     */
    private Source text(Context context, Item item) throws SQLException, IOException, AuthorizeException {
        Map<String, Bitstream> texts = new HashMap<>();
        for (Bundle b : itemService.getBundles(item, "TEXT")) {
            for (Bitstream bs : b.getBitstreams()) {
                texts.put(bs.getName(), bs);
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        Bitstream chosenOrig = null;
        Bitstream chosenText = null;
        for (Bundle b : itemService.getBundles(item, "ORIGINAL")) {
            Bitstream primary = b.getPrimaryBitstream();
            if (primary != null && texts.containsKey(primary.getName() + ".txt")) {
                chosenOrig = primary;
                chosenText = texts.get(primary.getName() + ".txt");
                break;
            }
            for (Bitstream o : b.getBitstreams()) {
                Bitstream t = texts.get(o.getName() + ".txt");
                if (t != null && (chosenText == null || t.getSizeBytes() > chosenText.getSizeBytes())) {
                    chosenOrig = o;
                    chosenText = t;
                }
            }
        }
        if (chosenText == null) {
            chosenText = texts.values().stream().max((a, c) -> Long.compare(a.getSizeBytes(), c.getSizeBytes()))
                .orElse(null);
        }
        if (chosenText == null) {
            return null;
        }
        String s;
        try (InputStream in = bitstreamService.retrieve(context, chosenText)) {
            s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean pdf = chosenOrig != null && chosenOrig.getFormat(context) != null
            && "application/pdf".equalsIgnoreCase(chosenOrig.getFormat(context).getMIMEType());
        return new Source(s, chosenText.getName(), pdf);
    }

    // ── language ────────────────────────────────────────────────────────────────────────────────────────────

    private record Lang(String code, String source, String metadata, String model) {
    }

    /**
     * Normalised metadata first. The model's language check runs when the metadata is missing or when
     * language.verify is on; when the two disagree the text's language is used and the run record says so.
     */
    private Lang language(Item item, TextCleaner.Result clean, KeywordPipeline p) throws LlmClient.LlmUnavailable {
        String field = prop("language.field", "dc.language.iso");
        Map<String, String> map = new HashMap<>();
        for (String pair : arr("language.map", "")) {
            int c = pair.lastIndexOf(':');
            if (c > 0) {
                map.put(pair.substring(0, c).trim().toLowerCase(Locale.ROOT), pair.substring(c + 1).trim());
            }
        }
        String meta = null;
        List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, field);
        if (!vals.isEmpty() && vals.get(0).getValue() != null) {
            String raw = vals.get(0).getValue().trim();
            String low = raw.toLowerCase(Locale.ROOT);
            meta = map.getOrDefault(low, low.length() == 2 ? low : null);
        }
        boolean verify = taskBooleanProperty("language.verify", false);
        if (meta != null && !verify) {
            return new Lang(meta, "metadata", meta, null);
        }
        LlmClient.Answer a = p.llm().language(clean.title(), clean.text());
        String model = a.ok() ? a.json().path("language").asText(null) : null;
        if ("other".equals(model)) {
            model = null;
        }
        if (meta == null) {
            return new Lang(model, model == null ? "unknown" : "text", null, model);
        }
        if (model != null && !model.equals(meta)) {
            return new Lang(model, "text (metadata says " + meta + ")", meta, model);
        }
        return new Lang(meta, "metadata, confirmed by the text", meta, model);
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────────────────

    private ObjectNode runRecord(KeywordPipeline.Outcome out, Lang lang, String kind, Source src,
                                 List<KeywordChecks.Checked> written, List<String> skippedApproved, boolean dryRun,
                                 double secs) {
        ObjectNode r = OM.createObjectNode();
        r.put("task", taskId);
        r.put("time", Instant.now().toString());
        r.put("dry_run", dryRun);
        r.put("model", prop("llm.model", "qwen3-4b-instruct-2507"));
        ObjectNode v = r.putObject("versions");
        new LinkedHashMap<>(out.versions()).forEach((k, x) -> v.put(k, String.valueOf(x)));
        r.put("language", lang.code());
        r.put("language_source", lang.source());
        r.put("kind", kind);
        r.put("text_bitstream", src.name());
        r.put("excerpt_tokens", out.excerpt().tokens());
        r.put("whole_text", out.excerpt().whole());
        r.put("prompt_tokens", out.answer().promptTokens());
        r.put("completion_tokens", out.answer().completionTokens());
        r.put("seconds", Math.round(secs * 10) / 10.0);
        ArrayNode ks = r.putArray("keywords");
        for (KeywordChecks.Checked k : written) {
            ObjectNode o = ks.addObject();
            o.put("keyword", k.keyword());
            o.put("evidence", k.evidence());
            ArrayNode f = o.putArray("flags");
            k.flags().forEach(f::add);
        }
        ArrayNode sa = r.putArray("already_in_dc_subject");
        skippedApproved.forEach(sa::add);
        return r;
    }

    /**
     * Keywords the previous run wrote that are no longer in the suggestion field: a cataloguer deleted or edited
     * them, which is a decision, so they are not suggested again. Reads the previous run record; writes nothing.
     */
    private Set<String> rejectedByCataloguer(Item item) {
        String[] sug = field(prop("field.suggested", "local.subject.suggested"));
        String[] rec = field(prop("field.run", "local.description.keywordrun"));
        Set<String> out = new HashSet<>();
        String prevJson = itemService.getMetadataFirstValue(item, rec[0], rec[1], rec[2], Item.ANY);
        if (prevJson == null) {
            return out;
        }
        Set<String> current = new HashSet<>();
        for (MetadataValue mv : itemService.getMetadata(item, sug[0], sug[1], sug[2], Item.ANY)) {
            if (mv.getValue() != null) {
                current.add(mv.getValue().toLowerCase(Locale.ROOT));
            }
        }
        try {
            JsonNode prev = OM.readTree(prevJson);
            if (prev.path("dry_run").asBoolean(false)) {
                return out;                            // a dry run wrote nothing, so nothing was removed
            }
            for (JsonNode k : prev.path("keywords")) {
                String low = k.path("keyword").asText().toLowerCase(Locale.ROOT);
                if (!current.contains(low)) {
                    out.add(low);
                }
            }
            for (JsonNode k : prev.path("removed_by_cataloguer_before")) {     // stays rejected on later runs
                out.add(k.asText().toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            log.warn("suggestkeywords: unreadable previous run record on {}", item.getID());
        }
        return out;
    }

    /**
     * Replaces the values this task wrote last time (listed in its previous run record) with the new ones. Values a
     * cataloguer added or edited stay; dc.subject is not touched.
     */
    private void write(Context context, Item item, List<KeywordChecks.Checked> keywords, ObjectNode run)
        throws SQLException, AuthorizeException, IOException {
        String[] sug = field(prop("field.suggested", "local.subject.suggested"));
        String[] rec = field(prop("field.run", "local.description.keywordrun"));
        Set<String> previous = new HashSet<>();
        String prevJson = itemService.getMetadataFirstValue(item, rec[0], rec[1], rec[2], Item.ANY);
        if (prevJson != null) {
            try {
                for (JsonNode k : OM.readTree(prevJson).path("keywords")) {
                    previous.add(k.path("keyword").asText());
                }
            } catch (IOException e) {
                log.warn("suggestkeywords: unreadable previous run record on {}", item.getID());
            }
        }
        List<MetadataValue> remove = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (MetadataValue mv : itemService.getMetadata(item, sug[0], sug[1], sug[2], Item.ANY)) {
            if (previous.contains(mv.getValue())) {
                remove.add(mv);
            } else {
                kept.add(mv.getValue().toLowerCase(Locale.ROOT));
            }
        }
        if (!remove.isEmpty()) {
            itemService.removeMetadataValues(context, item, remove);
        }
        for (KeywordChecks.Checked k : keywords) {
            if (!kept.contains(k.keyword().toLowerCase(Locale.ROOT))) {
                itemService.addMetadata(context, item, sug[0], sug[1], sug[2], null, k.keyword());
            }
        }
        itemService.clearMetadata(context, item, rec[0], rec[1], rec[2], Item.ANY);
        itemService.addMetadata(context, item, rec[0], rec[1], rec[2], null, OM.writeValueAsString(run));
        itemService.update(context, item);
    }

    private static String[] field(String f) {
        String[] p = f.split("\\.");
        return new String[] {p[0], p[1], p.length > 2 ? p[2] : null};
    }

    private Set<String> lowerValues(Item item, String field) {
        Set<String> out = new HashSet<>();
        for (MetadataValue v : itemService.getMetadataByMetadataString(item, field)) {
            if (v.getValue() != null) {
                out.add(v.getValue().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * A list setting. DSpace's configuration splits comma-separated values into a list, and taskProperty would
     * return only the first element; a run parameter is one string and is split here.
     */
    private List<String> arr(String name, String dflt) {
        String[] v = taskArrayProperty(name);
        List<String> out = new ArrayList<>();
        if (v == null || v.length == 0) {
            out.addAll(list(dflt));
        } else {
            for (String x : v) {
                out.addAll(list(x));
            }
        }
        return out;
    }

    private static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}

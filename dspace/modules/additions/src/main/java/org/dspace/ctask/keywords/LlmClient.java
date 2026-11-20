/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Calls an OpenAI-compatible chat endpoint (LM Studio) with a JSON schema, using the evaluated prompts
 * ("f-v1" for keywords, "language-v1" for the language check), temperature 0.
 */
public class LlmClient {

    public static final String KEYWORD_PROMPT_VERSION = "f-v1";
    public static final String LANGUAGE_PROMPT_VERSION = "language-v1";
    /** Languages the keyword prompt names (phase2/system_f.py LANG_NAMES). */
    public static final Map<String, String> LANG_NAMES = Map.of("hu", "Hungarian", "en", "English", "de", "German",
        "fr", "French");

    private static final ObjectMapper OM = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final String keywordSystem;
    private final JsonNode keywordSchema;
    private final String languageSystem;
    private final JsonNode languageSchema;

    public LlmClient(String baseUrl, String model, Duration timeout) throws IOException {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.timeout = timeout;
        this.keywordSystem = resource("prompt-keywords-f-v1.txt");
        this.keywordSchema = OM.readTree(resource("schema-keywords-f-v1.json"));
        this.languageSystem = resource("prompt-language-v1.txt");
        this.languageSchema = OM.readTree(resource("schema-language-v1.json"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = LlmClient.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The user message of the evaluated prompt (phase2/system_f.py user_message). */
    static String keywordUserMessage(String title, String text, boolean whole) {
        String head = title != null && !title.isEmpty() ? "Title: " + title + "\n" : "Title: (none)\n";
        String what = whole ? "Text:" : "Text (selected parts of a longer document, separated by […]):";
        return head + what + "\n" + text;
    }

    /** Keywords for the text; language is an ISO 639-1 code from {@link #LANG_NAMES}. One retry on invalid JSON. */
    public Answer keywords(String title, String text, boolean whole, String language) throws LlmUnavailable {
        String system = keywordSystem.replace("{lang}", LANG_NAMES.getOrDefault(language, language));
        Answer last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            last = call(system, keywordUserMessage(title, text, whole), keywordSchema, 800);
            if (last.ok()) {
                List<KeywordChecks.Raw> out = new ArrayList<>();
                for (JsonNode k : last.json().path("keywords")) {
                    out.add(new KeywordChecks.Raw(k.path("keyword").asText(""), k.path("evidence").asText("")));
                }
                return last.withKeywords(out);
            }
        }
        return last;
    }

    /** The evaluated language check: title plus a 1,500-character excerpt from 20 % into the text. */
    public Answer language(String title, String cleanText) throws LlmUnavailable {
        // as modellproba.record_text(...).replace("Abstract:", "Excerpt:") in the pilot
        String ex = languageExcerpt(cleanText == null ? "" : cleanText);
        String user = ("Title: " + (title == null || title.isEmpty() ? "(none)" : title) + "\nAbstract: "
            + (ex.isEmpty() ? "(none)" : ex)).replace("Abstract:", "Excerpt:");
        return call(languageSystem, user, languageSchema, 20);
    }

    /** phase0/language.py excerpt(): 1,500 characters starting at the first paragraph break after 20 %. */
    static String languageExcerpt(String text) {
        int size = 1500;
        if (PyText.len(text) <= size) {
            return text;
        }
        int[] cps = text.codePoints().toArray();
        int start = (int) (cps.length * 0.2);
        String s = new String(cps, 0, cps.length);
        int startChar = s.offsetByCodePoints(0, start);
        int endWindow = s.offsetByCodePoints(0, Math.min(cps.length, start + size));
        int nl = s.indexOf("\n\n", startChar);
        if (nl != -1 && nl + 2 <= endWindow) {
            start = s.codePointCount(0, nl) + 2;
        }
        return new String(cps, start, Math.min(size, cps.length - start));
    }

    private Answer call(String system, String user, JsonNode schema, int maxTokens) throws LlmUnavailable {
        ObjectNode body = OM.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", user);
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_schema");
        rf.set("json_schema", schema);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        long t0 = System.nanoTime();
        int status;
        String respBody;
        // HttpURLConnection, not java.net.http.HttpClient: the latter needs NIO pipes that fail on some Windows hosts
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL()
                .openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10_000);
            con.setReadTimeout((int) timeout.toMillis());
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = con.getOutputStream()) {
                os.write(OM.writeValueAsBytes(body));
            }
            status = con.getResponseCode();
            try (InputStream is = status < 400 ? con.getInputStream() : con.getErrorStream()) {
                respBody = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (SocketTimeoutException e) {
            throw new LlmUnavailable("timeout after " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new LlmUnavailable("cannot reach " + baseUrl + ": " + e.getMessage(), e);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        if (status != 200) {
            return Answer.failed("HTTP " + status + ": " + respBody.substring(0, Math.min(300, respBody.length())),
                secs);
        }
        try {
            JsonNode data = OM.readTree(respBody);
            JsonNode choice = data.path("choices").path(0);
            String content = choice.path("message").path("content").asText(null);
            JsonNode usage = data.path("usage");
            JsonNode json = content == null ? null : OM.readTree(content);
            if (json == null || !json.isObject()) {
                return Answer.failed("invalid JSON in the answer", secs);
            }
            return new Answer(true, json, List.of(), null, choice.path("finish_reason").asText(null),
                usage.path("prompt_tokens").asInt(-1), usage.path("completion_tokens").asInt(-1), secs);
        } catch (IOException e) {
            return Answer.failed("invalid JSON: " + e.getMessage(), secs);
        }
    }

    /** The model could not be reached or did not answer in time: the item gets ERROR, nothing is written. */
    public static class LlmUnavailable extends Exception {
        private static final long serialVersionUID = 1L;

        public LlmUnavailable(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** One model answer. */
    public record Answer(boolean ok, JsonNode json, List<KeywordChecks.Raw> keywords, String error,
                         String finishReason, int promptTokens, int completionTokens, double seconds) {
        static Answer failed(String error, double secs) {
            return new Answer(false, null, List.of(), error, null, -1, -1, secs);
        }

        Answer withKeywords(List<KeywordChecks.Raw> k) {
            return new Answer(ok, json, k, error, finishReason, promptTokens, completionTokens, seconds);
        }
    }
}

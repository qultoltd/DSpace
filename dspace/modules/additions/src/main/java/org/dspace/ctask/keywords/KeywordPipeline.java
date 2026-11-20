/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The keyword suggestion pipeline without DSpace: clean-up, baseline excerpt, the model call and the checks. The
 * curation task ({@link SuggestKeywords}) supplies the text and metadata and writes the result.
 */
public class KeywordPipeline {

    public static final String VERSION = "pipeline-v1";
    /** Excerpt budget in Qwen tokens: with the prompt (~400) and 800 answer tokens it fits a 4,096 context. */
    public static final int DEFAULT_BUDGET = 2800;

    private final TextCleaner cleaner;
    private final BaselineExcerpt excerpt;
    private final KeywordChecks checks;
    private final LlmClient llm;
    private final int budget;
    private final int maxKeywords;

    public KeywordPipeline(LlmClient llm, int budget, int maxKeywords) throws IOException {
        Speller speller = Speller.fromResources("hu_HU.aff.gz", "hu_HU.dic.gz");
        this.cleaner = new TextCleaner(speller);
        this.excerpt = new BaselineExcerpt(QwenTokenizer.fromResource("qwen3-tokenizer.json.gz"),
            BackgroundIdf.fromResource("background-idf-v2.json.gz"));
        this.checks = new KeywordChecks(speller);
        this.llm = llm;
        this.budget = budget;
        this.maxKeywords = maxKeywords;
    }

    /** Clean-up only (the task needs the clean text for the language check before deciding to go on). */
    public TextCleaner.Result clean(String rawText, String title, boolean pdf) {
        return cleaner.clean(rawText, title, pdf, true);
    }

    /**
     * Keywords for already cleaned text.
     *
     * @param literary a literary work that is processed (not fiction): genre/form words are removed
     */
    public Outcome suggest(TextCleaner.Result clean, String language, boolean literary)
        throws LlmClient.LlmUnavailable {
        BaselineExcerpt.Result ex = excerpt.select(clean.text(), budget);
        LlmClient.Answer a = llm.keywords(clean.title(), ex.text(), ex.whole(), language);
        if (!a.ok()) {
            return new Outcome(false, a.error(), List.of(), ex, a);
        }
        List<KeywordChecks.Checked> kws = checks.check(a.keywords(), clean.text(), ex.text(), literary, maxKeywords);
        return new Outcome(true, null, kws, ex, a);
    }

    public LlmClient llm() {
        return llm;
    }

    /** Result of one item: the checked keywords, the excerpt the model read, and the raw model answer. */
    public record Outcome(boolean ok, String error, List<KeywordChecks.Checked> keywords, BaselineExcerpt.Result excerpt,
                          LlmClient.Answer answer) {
        public Map<String, Object> versions() {
            return Map.of("pipeline", VERSION, "cleanup", TextCleaner.VERSION, "excerpt", BaselineExcerpt.VERSION,
                "checks", KeywordChecks.VERSION, "prompt", LlmClient.KEYWORD_PROMPT_VERSION);
        }
    }
}

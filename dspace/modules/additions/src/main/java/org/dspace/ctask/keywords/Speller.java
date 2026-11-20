/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.store.ByteBuffersDirectory;

/**
 * Hungarian spell check with Lucene's Hunspell implementation (already on DSpace's classpath) and the Magyar Ispell
 * dictionary (hu_HU, MPL 2.0 / LGPL 3+). Used where the pilot used pyenchant: joining line-break hyphens and guarding
 * the re-capitalisation of names.
 */
public class Speller {

    private final Hunspell hunspell;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public Speller(InputStream aff, InputStream dic) throws IOException {
        try {
            Dictionary d = new Dictionary(new ByteBuffersDirectory(), "hunspell", aff, dic);
            this.hunspell = new Hunspell(d);
        } catch (ParseException e) {
            throw new IOException("cannot read the Hunspell dictionary", e);
        }
    }

    /** Loads hu_HU.aff and hu_HU.dic (".gz" names are decompressed) from the classpath. */
    public static Speller fromResources(String aff, String dic) throws IOException {
        try (InputStream a = open(aff); InputStream d = open(dic)) {
            return new Speller(a, d);
        }
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = Speller.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("resource not found: " + name);
        }
        return name.endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    /** True if the dictionary knows the word. Empty or blank strings count as known, as in the pilot. */
    public boolean known(String word) {
        if (word.isBlank()) {
            return true;
        }
        return cache.computeIfAbsent(word, w -> {
            try {
                // Measured against the pilot's checker (pyenchant + libhunspell) on 25,000 corpus words: 0.7 % differ.
                // Lucene accepts some lower-case names ("vivaldi") and misses some compounds ("csendőrlaktanya").
                // Rejecting lower-case words with only capitalised roots was tried and made it worse (it also rejects
                // correct adjectives such as "szicíliai"), so the difference is accepted and documented.
                return hunspell.spell(w);
            } catch (RuntimeException e) {     // a pathological word must not stop the task
                return false;
            }
        });
    }
}

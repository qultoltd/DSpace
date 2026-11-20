/**
 * The contents of this file are subject to the license and copyright detailed in the
 * LICENSE and NOTICE files at the root of the source tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.keywords;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Background document frequencies of Snowball stems, for specificity (IDF). The table was built in the pilot from
 * 20,000 parliamentary speeches and 18,287 chunks of PIM DIA books ({@code background_v2.json}):
 * {@code {"units": {"speeches": n, "dia": n}, "df": {stem: [df_speeches, df_dia]}}}.
 * idf(stem) = ln((N + 1) / (df + 1)), N = all units, df = the sum of both counts.
 */
public class BackgroundIdf {

    private final int units;
    private final Map<String, Integer> df = new HashMap<>(300_000);

    public BackgroundIdf(InputStream json) throws IOException {
        JsonParser p = new ObjectMapper().getFactory().createParser(json);
        int n = 0;
        p.nextToken();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            if ("units".equals(field)) {
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    p.nextToken();
                    n += p.getIntValue();
                }
            } else if ("df".equals(field)) {
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String stem = p.currentName();
                    p.nextToken();                       // START_ARRAY
                    int sum = 0;
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        sum += p.getIntValue();
                    }
                    df.put(stem, sum);
                }
            } else {
                p.skipChildren();
            }
        }
        this.units = n;
    }

    public static BackgroundIdf fromResource(String name) throws IOException {
        InputStream in = BackgroundIdf.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("resource not found: " + name);
        }
        try (InputStream s = name.endsWith(".gz") ? new GZIPInputStream(in) : in) {
            return new BackgroundIdf(s);
        }
    }

    public double idf(String stem) {
        return Math.log((units + 1.0) / (df.getOrDefault(stem, 0) + 1.0));
    }

    public double maxIdf() {
        return Math.log(units + 1.0);
    }

    public int units() {
        return units;
    }
}

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.lucene.all.AllTokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.SynonymTokenFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.hamcrest.MatcherAssert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;

public class SynonymsAnalysisTests extends ESTestCase {
    private IndexAnalyzers indexAnalyzers;

    public void testSynonymsAnalysis() throws IOException {
        InputStream synonyms = getClass().getResourceAsStream("synonyms.txt");
        InputStream synonymsWordnet = getClass().getResourceAsStream("synonyms_wordnet.txt");
        Path home = createTempDir();
        Path config = home.resolve("config");
        Files.createDirectory(config);
        Files.copy(synonyms, config.resolve("synonyms.txt"));
        Files.copy(synonymsWordnet, config.resolve("synonyms_wordnet.txt"));

        String json = "/org/elasticsearch/analysis/common/synonyms.json";
        Settings settings = Settings.builder().
            loadFromStream(json, getClass().getResourceAsStream(json), false)
                .put(Environment.PATH_HOME_SETTING.getKey(), home)
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build();

        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        match("synonymAnalyzer", "kimchy is the dude abides", "shay is the elasticsearch man!");
        match("synonymAnalyzer_file", "kimchy is the dude abides", "shay is the elasticsearch man!");
        match("synonymAnalyzerWordnet", "abstain", "abstain refrain desist");
        match("synonymAnalyzerWordnet_file", "abstain", "abstain refrain desist");
        match("synonymAnalyzerWithsettings", "kimchy", "sha hay");
        match("synonymAnalyzerWithStopAfterSynonym", "kimchy is the dude abides , stop", "shay is the elasticsearch man! ,");
        match("synonymAnalyzerWithStopBeforeSynonym", "kimchy is the dude abides , stop", "shay is the elasticsearch man! ,");
        match("synonymAnalyzerWithStopSynonymAfterSynonym", "kimchy is the dude abides", "shay is the man!");
        match("synonymAnalyzerExpand", "kimchy is the dude abides", "kimchy shay is the dude elasticsearch abides man!");
        match("synonymAnalyzerExpandWithStopAfterSynonym", "kimchy is the dude abides", "shay is the dude abides man!");

    }

    public void testSynonymWordDeleteByAnalyzer() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonym.type", "synonym")
            .putList("index.analysis.filter.synonym.synonyms", "kimchy => shay", "dude => elasticsearch", "abides => man!")
            .put("index.analysis.filter.stop_within_synonym.type", "stop")
            .putList("index.analysis.filter.stop_within_synonym.stopwords", "kimchy", "elasticsearch")
            .put("index.analysis.analyzer.synonymAnalyzerWithStopSynonymBeforeSynonym.tokenizer", "whitespace")
            .putList("index.analysis.analyzer.synonymAnalyzerWithStopSynonymBeforeSynonym.filter", "stop_within_synonym","synonym")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        try {
            indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;
            fail("fail! due to synonym word deleted by analyzer");
        } catch (Exception e) {
            assertThat(e, instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), startsWith("failed to build synonyms"));
        }
    }

    public void testExpandSynonymWordDeleteByAnalyzer() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonym_expand.type", "synonym")
            .putList("index.analysis.filter.synonym_expand.synonyms", "kimchy, shay", "dude, elasticsearch", "abides, man!")
            .put("index.analysis.filter.stop_within_synonym.type", "stop")
            .putList("index.analysis.filter.stop_within_synonym.stopwords", "kimchy", "elasticsearch")
            .put("index.analysis.analyzer.synonymAnalyzerExpandWithStopBeforeSynonym.tokenizer", "whitespace")
            .putList("index.analysis.analyzer.synonymAnalyzerExpandWithStopBeforeSynonym.filter", "stop_within_synonym","synonym_expand")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        try {
            indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;
            fail("fail! due to synonym word deleted by analyzer");
        } catch (Exception e) {
            assertThat(e, instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), startsWith("failed to build synonyms"));
        }
    }

    public void testSynonymsWrappedByMultiplexer() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonyms.type", "synonym")
            .putList("index.analysis.filter.synonyms.synonyms", "programmer, developer")
            .put("index.analysis.filter.my_english.type", "stemmer")
            .put("index.analysis.filter.my_english.language", "porter2")
            .put("index.analysis.filter.stem_repeat.type", "multiplexer")
            .putList("index.analysis.filter.stem_repeat.filters", "my_english, synonyms")
            .put("index.analysis.analyzer.synonymAnalyzer.tokenizer", "standard")
            .putList("index.analysis.analyzer.synonymAnalyzer.filter", "lowercase", "stem_repeat")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        BaseTokenStreamTestCase.assertAnalyzesTo(indexAnalyzers.get("synonymAnalyzer"), "Some developers are odd",
            new String[]{ "some", "developers", "develop", "programm", "are", "odd" },
            new int[]{ 1, 1, 0, 0, 1, 1 });
    }

    public void testAsciiFoldingFilterForSynonyms() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonyms.type", "synonym")
            .putList("index.analysis.filter.synonyms.synonyms", "hoj, height")
            .put("index.analysis.analyzer.synonymAnalyzer.tokenizer", "standard")
            .putList("index.analysis.analyzer.synonymAnalyzer.filter", "lowercase", "asciifolding", "synonyms")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        BaseTokenStreamTestCase.assertAnalyzesTo(indexAnalyzers.get("synonymAnalyzer"), "høj",
            new String[]{ "hoj", "height" },
            new int[]{ 1, 0 });
    }

    public void testKeywordRepeatAndSynonyms() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonyms.type", "synonym")
            .putList("index.analysis.filter.synonyms.synonyms", "programmer, developer")
            .put("index.analysis.filter.my_english.type", "stemmer")
            .put("index.analysis.filter.my_english.language", "porter2")
            .put("index.analysis.analyzer.synonymAnalyzer.tokenizer", "standard")
            .putList("index.analysis.analyzer.synonymAnalyzer.filter", "lowercase", "keyword_repeat", "my_english", "synonyms")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        BaseTokenStreamTestCase.assertAnalyzesTo(indexAnalyzers.get("synonymAnalyzer"), "programmers",
            new String[]{ "programmers", "programm", "develop" },
            new int[]{ 1, 0, 0 });
    }

    public void testChainedSynonymFilters() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonyms1.type", "synonym")
            .putList("index.analysis.filter.synonyms1.synonyms", "term1, term2")
            .put("index.analysis.filter.synonyms2.type", "synonym")
            .putList("index.analysis.filter.synonyms2.synonyms", "term1, term3")
            .put("index.analysis.analyzer.syn.tokenizer", "standard")
            .putList("index.analysis.analyzer.syn.filter", "lowercase", "synonyms1", "synonyms2")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        BaseTokenStreamTestCase.assertAnalyzesTo(indexAnalyzers.get("syn"), "term1",
            new String[]{ "term1", "term3", "term2" }, new int[]{ 1, 0, 0 });
    }

    public void testShingleFilters() throws IOException {

        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("index.analysis.filter.synonyms.type", "synonym")
            .putList("index.analysis.filter.synonyms.synonyms", "programmer, developer")
            .put("index.analysis.filter.my_shingle.type", "shingle")
            .put("index.analysis.analyzer.my_analyzer.tokenizer", "standard")
            .putList("index.analysis.analyzer.my_analyzer.filter", "my_shingle", "synonyms")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);

        indexAnalyzers = createTestAnalysis(idxSettings, settings, new CommonAnalysisPlugin()).indexAnalyzers;

        assertWarnings("Token filter [my_shingle] will not be usable to parse synonyms after v7.0");
    }

    public void testTokenFiltersBypassSynonymAnalysis() throws IOException {

        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .putList("word_list", "a")
            .put("hyphenation_patterns_path", "foo")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);

        String[] bypassingFactories = new String[]{
            "dictionary_decompounder"
        };

        CommonAnalysisPlugin plugin = new CommonAnalysisPlugin();
        for (String factory : bypassingFactories) {
            TokenFilterFactory tff = plugin.getTokenFilters().get(factory).get(idxSettings, null, factory, settings);
            TokenizerFactory tok = new KeywordTokenizerFactory(idxSettings, null, "keyword", settings);
            SynonymTokenFilterFactory stff = new SynonymTokenFilterFactory(idxSettings, null, null, "synonym", settings);
            Analyzer analyzer = stff.buildSynonymAnalyzer(tok, Collections.emptyList(), Collections.singletonList(tff));

            try (TokenStream ts = analyzer.tokenStream("field", "text")) {
                assertThat(ts, instanceOf(KeywordTokenizer.class));
            }
        }

    }

    public void testDisallowedTokenFilters() throws IOException {

        Settings settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .putList("common_words", "a", "b")
            .put("output_unigrams", "true")
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        CommonAnalysisPlugin plugin = new CommonAnalysisPlugin();

        String[] disallowedFactories = new String[]{
            "multiplexer", "cjk_bigram", "common_grams", "ngram", "edge_ngram",
            "word_delimiter", "word_delimiter_graph", "fingerprint"
        };

        List<String> expectedWarnings = new ArrayList<>();
        for (String factory : disallowedFactories) {
            TokenFilterFactory tff = plugin.getTokenFilters().get(factory).get(idxSettings, null, factory, settings);
            TokenizerFactory tok = new KeywordTokenizerFactory(idxSettings, null, "keyword", settings);
            SynonymTokenFilterFactory stff = new SynonymTokenFilterFactory(idxSettings, null, null, "synonym", settings);

            stff.buildSynonymAnalyzer(tok, Collections.emptyList(), Collections.singletonList(tff));
            expectedWarnings.add("Token filter [" + factory
                + "] will not be usable to parse synonyms after v7.0");
        }

        assertWarnings(expectedWarnings.toArray(new String[0]));

        settings = Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("path.home", createTempDir().toString())
            .put("preserve_original", "false")
            .build();
        idxSettings = IndexSettingsModule.newIndexSettings("index", settings);
        TokenFilterFactory tff = plugin.getTokenFilters().get("multiplexer").get(idxSettings, null, "multiplexer", settings);
        TokenizerFactory tok = new KeywordTokenizerFactory(idxSettings, null, "keyword", settings);
        SynonymTokenFilterFactory stff = new SynonymTokenFilterFactory(idxSettings, null, null, "synonym", settings);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
            () -> stff.buildSynonymAnalyzer(tok, Collections.emptyList(), Collections.singletonList(tff)));

        assertEquals("Token filter [multiplexer] cannot be used to parse synonyms unless [preserve_original] is [true]",
            e.getMessage());

    }

    private void match(String analyzerName, String source, String target) throws IOException {
        Analyzer analyzer = indexAnalyzers.get(analyzerName).analyzer();

        TokenStream stream = AllTokenStream.allTokenStream("_all", source, 1.0f, analyzer);
        stream.reset();
        CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);

        StringBuilder sb = new StringBuilder();
        while (stream.incrementToken()) {
            sb.append(termAtt.toString()).append(" ");
        }

        MatcherAssert.assertThat(target, equalTo(sb.toString().trim()));
    }

}

package edu.isi.vista.aceIndexer;

import com.google.common.collect.Lists;
import edu.isi.nlp.parameters.Parameters;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Date;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Indexes Ace with Elastic Search in a way usable by the external search feature of the Inception
 * annotator.
 */
public class IndexAceWithElasticSearch {
    //TODO: Modify these completely:
    private static final String USAGE = "IndexAceWithElasticSearch param_file\n" +
            "\tparam file consists of :-separated key-value pairs\n" +
            "\tThe required parameters are:\n" +
            "\tindexName: the name of the index in a running Elastic Search server to add the documents to\n" +
            "\tcorpusDirectoryPath: the path to a directory where corpus has been extracted. \n" +
            "\tformat: annotated_ace, or ace" +
            "\tcompressed: true if the documents are expected to be compressed, false otherwise" +
            "\tthreshold: a number between 0 and 1 indicating the percentage of failed indexing doc before terminating program\n" +
            "Additional parameters can be used to point to an Elastic Search server running somewhere besides the " +
            "standard ports on localhost. For these, please see the source code.";


    private static final Logger log = LoggerFactory.getLogger(IndexAceWithElasticSearch.class);

    private static final String DEFAULT_HOST = "localhost";

    private static final int DEFAULT_PORT_PRI = 9200;

    private static final int DEFAULT_PORT_SEC = 9201;

    private static final String PARAM_CORPUS_DIRECTORY_PATH = "corpusDirectoryPath";

    private static final String PARAM_FORMAT = "format";

    // Still Needed? since the ACE corpus comes as a single .tgz containing all the documents
    //  organized in various directories (max depth is 5).
    private static final String PARAM_COMPRESSED = "compressed";

    // NOTE: Languages subdirectories are inside the data directory
    private static final String PARAM_LANGUAGE = "lang";

    private static final String PARAM_INDEX_NAME = "indexName";

    private static final String PARAM_HOSTNAME_PRIMARY = "primaryHostName";

    private static final String PARAM_HOSTNAME_SECONDARY = "secondaryHostName";

    private static final String PARAM_PORT_PRIMARY = "primaryPort";

    private static final String PARAM_PORT_SECONDARY = "secondaryPort";

    private static final String PARAM_FRACTIOIN_DOCS_ALLOWED_TO_FAIL = "fractionDocsAllowedToFail";

    private static final String SENTENCE_LIMIT = "sentenceLimit";

    /**
     * Limits how many documents will be indexed. This is useful mostly for testing purposes.
     */
    private static final String MAX_DOCS_TO_PROCESS_PARAM = "maxDocsToProcess";

    private static int totalDoc = 0;

    private static int indexFailed = 0;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static OptionalInt maxDocumentsToIndex = OptionalInt.empty();

    private static final int BATCH_SIZE = 100;

    public static void main(String [] argv) throws IOException{

        // get parameter file
        Parameters parameters = null;

        if (argv.length == 1) {
            parameters = Parameters.loadSerifStyle(new File(argv[0]));
        } else {
            System.err.println(USAGE);
            System.exit(1);
        }

        try (RestHighLevelClient client = buildElasticSearchClient(parameters)) {
            final String indexName = parameters.getString(PARAM_INDEX_NAME);
            final String format = parameters.getString(PARAM_FORMAT);
            final boolean compressed = parameters.getOptionalBoolean(PARAM_COMPRESSED).or(true);
            final String lang = parameters.getOptionalString(PARAM_LANGUAGE).or("EN");
            final double fractionDocAllowToFail =
                    Double.parseDouble(parameters.getOptionalString(PARAM_FRACTIOIN_DOCS_ALLOWED_TO_FAIL)
                            .or("0.0"));
            final int sentenceLimit = parameters.getOptionalInteger(SENTENCE_LIMIT).or(100);
            final Path corpusDirPath = parameters.getExistingDirectory(PARAM_CORPUS_DIRECTORY_PATH).toPath();
            if (parameters.isPresent(MAX_DOCS_TO_PROCESS_PARAM)) {
                maxDocumentsToIndex = OptionalInt.of(
                        parameters.getPositiveInteger(MAX_DOCS_TO_PROCESS_PARAM));
            }
            PathMatcher filePattern;
            // Source files (unannotated) are in the .sgm format
            // Only checks each adj subdirectory to avoid duplicate source files
            // (as fp1, fp2 and timex2 use the same source files)
            if (lang.equalsIgnoreCase("english")) {
                filePattern = FileSystems.getDefault()
                        .getPathMatcher("glob:**/English/**/adj/*.sgm");
            } else if (lang.equalsIgnoreCase("chinese")){
                filePattern = FileSystems.getDefault()
                        .getPathMatcher("glob:**/Chinese/**/adj/*.sgm");
            } else if (lang.equalsIgnoreCase("arabic")) {
                filePattern = FileSystems.getDefault()
                        .getPathMatcher("glob:**/Arabic/**/adj/*.sgm");
            } else {
                throw new RuntimeException("The ACE corpus does not contain files of the " +
                        "specified language");
            }

            try (Stream<Path> corpusFiles = Files.walk(corpusDirPath)) {
                List<Path> corpusFilesList = corpusFiles.filter(filePattern::matches).collect(Collectors.toList());
                final Iterable<List<Path>> batchedArticlePaths = Lists.partition(corpusFilesList,
                        BATCH_SIZE);
                boolean shouldContinue = index(client, batchedArticlePaths, indexName, lang,
                        fractionDocAllowToFail, sentenceLimit);
                if (!shouldContinue) {
                    log.info(
                            "Indexing terminated early without error, probably due to the user "
                                    + "requesting a limit on the number of documents indexed");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log.info("{} documents indexed, {} failed", totalDoc-indexFailed, indexFailed);
        } catch (Exception e) {
            log.error("Indexing failed with an exception:", e);
            System.exit(1);
        }
    }

    private static RestHighLevelClient buildElasticSearchClient(Parameters parameters) {
        return new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(
                                parameters.getOptionalString(PARAM_HOSTNAME_PRIMARY).or(DEFAULT_HOST),
                                parameters
                                        .getOptionalPositiveInteger(PARAM_PORT_PRIMARY)
                                        .or(DEFAULT_PORT_PRI),
                                "http"),
                        new HttpHost(
                                parameters.getOptionalString(PARAM_HOSTNAME_SECONDARY).or(DEFAULT_HOST),
                                parameters
                                        .getOptionalPositiveInteger(PARAM_PORT_SECONDARY)
                                        .or(DEFAULT_PORT_SEC),
                                "http")));
    }

    /**
     * Indexes the documents at the provided Paths.
     *
     * Returns whether or not the indexing process should continue.
     */
    private static boolean index(
            RestHighLevelClient client,
            Iterable<List<Path>> iterator,
            String indexName,
            String lang,
            double fractionDocAllowToFail,
            int sentenceLimit) throws IOException {

        for (List<Path> articlePaths : iterator) {
            final BulkRequest bulkRequest = new BulkRequest();
            for (Path articlePath : articlePaths) {
                Article article = articleFromPath(articlePath);
                if (article.failed()) { // error occurred
                    indexFailed += 1;
                    if ((double)indexFailed/(double)(totalDoc) > fractionDocAllowToFail) {
                        throw new RuntimeException("Failed documents exceeded threshold");
                    }
                } else if (article.getSegments() > sentenceLimit) {
                    indexFailed += 1;
                    log.warn("Document not indexed because it exceeded the size limit of {}: {}, {}",
                            sentenceLimit , article.getSegments(), article.getId());
                } else {
                    XContentBuilder sourceBuilder =
                            buildSourceObject(article, lang, "", new Date().toString(), "");
                    bulkRequest.add(
                            new IndexRequest(indexName, "texts", article.getId()).source(sourceBuilder));
                }
                totalDoc += 1;
            }
            if (bulkRequest.numberOfActions() > 0) {
                BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
                if (bulkResponse.hasFailures()) {
                    throw new RuntimeException(bulkResponse.buildFailureMessage());
                }
            }

            if (maxDocumentsToIndex.isPresent() && totalDoc >= maxDocumentsToIndex.getAsInt()) {
                return false;
            }
        }
        return true;
    }

    private static XContentBuilder buildSourceObject(
            Article article, String language, String source, String timestamp, String uri)
            throws IOException {

        return XContentFactory.jsonBuilder()
                .startObject()
                .startObject("doc")
                .field("text", article.getText())
                .endObject()
                .startObject("metadata")
                .field("id", article.getId())
                .field("language", language)
                .field("source", source)
                .field("timestamp", timestamp)
                .field("uri", uri)
                .endObject()
                .endObject();
    }

    private static Article articleFromPath(Path filePath) throws IOException{

        String docString = new String(Files.readAllBytes(filePath));
        // DOC ID marker
        Pattern ACE_DOC_ID_PATTERN = Pattern.compile("<DOCID> (.*?) <.DOCID>");

        Matcher m =
                ACE_DOC_ID_PATTERN.matcher(
                        docString.substring(0, Math.min(120, docString.length())));
        if (m.find()) {
            String docId = m.group(1);
            return new Article(docId, docString);
        } else {
            throw new RuntimeException("Missing document ID on article");
        }
    }
}


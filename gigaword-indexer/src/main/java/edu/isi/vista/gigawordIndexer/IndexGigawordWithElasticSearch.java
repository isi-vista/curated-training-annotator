package edu.isi.vista.gigawordIndexer;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Date;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static com.google.common.collect.Iterables.partition;

/**
 * Indexes Gigaword with Elastic Search in a way usable by the external search feature of the Inception annotator.
 * The expected Gigaword version is LDC2011T07 (plain version)
 *
 * See usage message for details.
 */
public class IndexGigawordWithElasticSearch {
  private static final String USAGE = "IndexGigawordWithElasticSearch param_file\n" +
          "\tparam file consists of :-separated key-value pairs\n" +
          "\tThe required parameters are:\n" +
          "\tindexName: the name of the index in a running Elastic Search server to add the documents to\n" +
          "\tcorpusDirectoryPath: the path to a directory where corpus (i.e. LDC2011T07 English Gigaword 5th edition)\n" +
          "\t\thas been extracted. \n" +
          "\tformat: LTF, annotated_gigaword or gigaword" +
          "\tcompressed: true if the gigaword documents are expected to be compressed, false otherwise" +
          "\tthreshold: a number between 0 and 1 indicating the percentage of failed indexing doc before terminating program\n" +
          "Additional parameters can be used to point to an Elastic Search server running somewhere besides the " +
          "standard ports on localhost. For these, please see the source code.";


  private static final Logger log = LoggerFactory.getLogger(IndexGigawordWithElasticSearch.class);

  private static final String DEFAULT_HOST = "localhost";

  private static final int DEFAULT_PORT_PRI = 9200;

  private static final int DEFAULT_PORT_SEC = 9201;

  private static final String PARAM_CORPUS_DIRECTORY_PATH = "corpusDirectoryPath";

  private static final String PARAM_FORMAT = "format";

  private static final String PARAM_COMPRESSED = "compressed";

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

  // make error-prone not complain about the use of allMatch below
  @SuppressWarnings("ReturnValueIgnored")
  public static void main(String[] argv) throws IOException {

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
      if (format.equalsIgnoreCase("LTF")) {
        filePattern = FileSystems.getDefault().getPathMatcher("glob:**.ltf.zip");
      } else if (compressed){
        filePattern = FileSystems.getDefault().getPathMatcher("glob:**/data/**/*.gz");
      } else {
        filePattern = FileSystems.getDefault().getPathMatcher("glob:**/data/**/**");
      }


      try (Stream<Path> corpusFiles = Files.walk(corpusDirPath)) {
        //noinspection ResultOfMethodCallIgnored
        corpusFiles
            .filter(filePattern::matches)
            // we use allMatch because the inner code will return a boolean indicating whether to
            // continue
            .allMatch(
                concatenatedFile -> {
                    try (ArticleSource articleSource = getArticleSource(format, compressed,
                            concatenatedFile)) {
                      // we batch the documents in groups of 100 so we can get the efficiency gains
                      // from batching without making huge requests of unbounded size
                      final Iterable<List<Article>> batchedArticles = partition(articleSource, BATCH_SIZE);

                      boolean shouldContinue = index(client, batchedArticles, indexName, lang,
                              fractionDocAllowToFail, sentenceLimit);
                      if (!shouldContinue) {
                        log.info(
                                "Indexing terminated early without error, probably due to the user "
                                        + "requesting a limit on the number of documents indexed");
                        return false;
                      }
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return true;
                });
      }

      log.info("{} documents indexed, {} failed", totalDoc-indexFailed, indexFailed);
    } catch (Exception e) {
      log.error("Indexing failed with an exception:", e);
      System.exit(1);
    }
  }

  private static ArticleSource getArticleSource(String format, boolean compressed,
          Path concatenatedFile) throws Exception
  {
    if (format.equalsIgnoreCase("ltf")) {
      return LTFDocuments.fromLTFZippedFile(concatenatedFile);
    } else if (format.equalsIgnoreCase("annotated_gigaword")) {
      log.warn("Indexing an annotated version of Gigaword.");
      return ConcatenatedAnnotatedGigawordDocuments.fromAnnotatedGigwordGZippedFile(concatenatedFile);
    } else if (format.equalsIgnoreCase("gigaword")) {
        if (compressed) {
          return ConcatenatedGigawordDocuments.fromGigwordGZippedFile(concatenatedFile);
        } else {
          return ConcatenatedGigawordDocuments.fromGigawordFile(concatenatedFile);
        }
    } else {
      throw new RuntimeException("Unknown input for parameter format. " +
              "Possible values are \"ltf\", \"annotated_gigaword\" and \"gigaword\".");
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
   * Indexes the provided documents.
   *
   * Returns whether or not the indexing process should continue.
   */
  private static boolean index(
      RestHighLevelClient client,
      Iterable<List<Article>> iterator,
      String indexName,
      String lang,
      double fractionDocAllowToFail,
      int sentenceLimit) throws IOException {

    for (List<Article> articles : iterator) {
      final BulkRequest bulkRequest = new BulkRequest();
      for (Article article : articles) {
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

  /**
   * The source format is according to Inception's ElasticSearch-based external search.
   *
   * <p>_source : { doc : { text : {} }, metadata : { id : {}, language : {}, source : {}, timestamp
   * : {}, uri : {} } }
   *
   * <p>Reference Inception's source code for ElasticSearchSource:
   * https://github.com/inception-project/inception/blob/master/inception-external-search-elastic/src/main/java/de/tudarmstadt/ukp/inception/externalsearch/elastic/model/ElasticSearchSource.java
   */
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
}

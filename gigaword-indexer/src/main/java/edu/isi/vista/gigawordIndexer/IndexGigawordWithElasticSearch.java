package edu.isi.vista.gigawordIndexer;

import edu.isi.nlp.parameters.Parameters;
import edu.isi.vista.gigawordIndexer.ConcatenatedGigawordDocuments.Article;
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
          "\tgigawordDirectoryPath: the path to a directory where LDC2011T07 (English Gigaword 5th edition)\n" +
          "\t\thas been extracted. \n" +
          "\tMake sure the version is the plain Gigaword (LDC2011T07) file and not the other versions." +
          "\n" +
          "Additional parameters can be used to point to an Elastic Search server running somewhere besides the " +
          "standard ports on localhost. For these, please see the source code.";


  private static final Logger log = LoggerFactory.getLogger(IndexGigawordWithElasticSearch.class);

  private static final String DEFAULT_HOST = "localhost";

  private static final int DEFAULT_PORT_PRI = 9200;

  private static final int DEFAULT_PORT_SEC = 9201;

  private static final String PARAM_GIGAWORD_FILEPATH = "gigawordFilePath";

  private static final String PARAM_GIGAWORD_DIRECTORY_PATH = "gigawordDirectoryPath";

  private static final String PARAM_INDEX_NAME = "indexName";

  private static final String PARAM_HOSTNAME_PRIMARY = "primaryHostName";

  private static final String PARAM_HOSTNAME_SECONDARY = "secondaryHostName";

  private static final String PARAM_PORT_PRIMARY = "primaryPort";

  private static final String PARAM_PORT_SECONDARY = "secondaryPort";

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
      if (parameters.isPresent(PARAM_GIGAWORD_DIRECTORY_PATH)) {
        final PathMatcher gzippedConcatenatedFilePattern = FileSystems.getDefault().getPathMatcher(
                "glob:**/data/**/*.gz");
        final Path gigawordDir = parameters.getExistingDirectory(PARAM_GIGAWORD_DIRECTORY_PATH).toPath();
        try (Stream<Path> gigawordFiles = Files.walk(gigawordDir)) {
          gigawordFiles
                  .filter(gzippedConcatenatedFilePattern::matches)
                  .forEach(gzippedConcatenatedFile -> {
                    try {
                          index(client, gzippedConcatenatedFile, indexName);
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  });
        }
      } else {
        File concatenatedFileToIndex = parameters.getExistingFile(PARAM_GIGAWORD_FILEPATH);
        index(client, concatenatedFileToIndex.toPath(), indexName);
      }

    } catch (Exception e) {
      log.error("Caught exception: {}", e);
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

  private static void index(RestHighLevelClient client, Path file, String indexName) throws IOException {
    log.info("Indexing {}", file.toAbsolutePath());

    Iterable<ConcatenatedGigawordDocuments.Article> concatenatedGigawordDocuments;
    // If file ends with .xml.gz, this may be the wrong version of Gigaword.
    if (file.toString().toLowerCase().endsWith(".xml.gz")) {
      log.warn("Indexing file ending with .xml.gz. This may indicate incorrect Gigaword version. "
          + "Make sure you are using the plain version LDC2011T07.");
      concatenatedGigawordDocuments = ConcatenatedAnnotatedGigawordDocuments.fromAnnotatedGigwordGZippedFile(file);
    }
    else
    {
      concatenatedGigawordDocuments = ConcatenatedGigawordDocuments.fromGigwordGZippedFile(file);
    }

    // we batch the documents in groups of 100 so we can get the efficiency gains from batching without
    // making huge requests of unbounded size
    for (List<Article> articles : partition(concatenatedGigawordDocuments, 100)) {
      final BulkRequest bulkRequest = new BulkRequest();
      for (Article article : articles) {
        System.out.println(article.getId());
        XContentBuilder sourceBuilder =
            buildSourceObject(article, "en", "", new Date().toString(), "");
        bulkRequest.add(
            new IndexRequest(indexName, "texts", article.getId()).source(sourceBuilder));
      }

      BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
      if (bulkResponse.hasFailures()) {
        throw new RuntimeException(bulkResponse.buildFailureMessage());
      }
    }
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

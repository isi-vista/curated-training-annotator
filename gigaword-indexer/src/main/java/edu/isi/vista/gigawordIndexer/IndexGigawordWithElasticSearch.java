package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;

import edu.isi.nlp.parameters.Parameters;
import edu.isi.vista.gigawordIndexer.GigawordFileProcessor.Article;

public class IndexGigawordWithElasticSearch {

  private static final Logger log = LoggerFactory.getLogger(IndexGigawordWithElasticSearch.class);

  private static final String DEFAULT_INDEX_NAME = "Gigaword";

  private static final String DEFAULT_HOST = "localhost";

  private static final int DEFAULT_PORT_PRI = 9200;

  private static final int DEFAULT_PORT_SEC = 9201;

  private static final String PARAM_FILEPATH = "filePath";

  private static final String PARAM_INDEX_NAME = "indexName";

  private static final String PARAM_HOSTNAME_PRI = "primaryHostName";

  private static final String PARAM_HOSTNAME_SEC = "secondaryHostName";

  private static final String PARAM_PORT_PRI = "primaryPort";

  private static final String PARAM_PORT_SEC = "secondaryPort";

  /**
   * @param argv takes two arguments, a path to the GZip file and an index name
   * @throws IOException
   */
  public static void main(String[] argv) throws IOException {

    // get parameter file
    final Parameters parameters;

    if (argv.length > 0) {
      parameters = Parameters.loadSerifStyle(new File(argv[0]));
    } else {
      log.error("Input file required");
      return;
    }

    // validate and parse inputs
    parameters.assertExactlyOneDefined(PARAM_FILEPATH);

    String indexName = parameters.getOptionalString(PARAM_INDEX_NAME).or(DEFAULT_INDEX_NAME);
    File file = parameters.getExistingFile(PARAM_FILEPATH);

    String contentType = Files.probeContentType(file.toPath());
    if ((contentType != null && !contentType.equalsIgnoreCase("application/gzip"))
        || (contentType == null
            && !FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("gz"))) {
      log.error("{} is not a valid GZip file", file.getAbsolutePath());
      return;
    }

    // start indexing
    try {

      RestHighLevelClient client =
          new RestHighLevelClient(
              RestClient.builder(
                  new HttpHost(
                      parameters.getOptionalString(PARAM_HOSTNAME_PRI).or(DEFAULT_HOST),
                      parameters.getOptionalPositiveInteger(PARAM_PORT_PRI).or(DEFAULT_PORT_PRI),
                      "http"),
                  new HttpHost(
                      parameters.getOptionalString(PARAM_HOSTNAME_SEC).or(DEFAULT_HOST),
                      parameters.getOptionalPositiveInteger(PARAM_PORT_SEC).or(DEFAULT_PORT_SEC),
                      "http")));

      // indexing
      index(client, file, indexName);
      client.close();

    } catch (Exception e) {
      log.error("Caught exception: {}", e);
      return;
    }
  }

  public static void index(RestHighLevelClient client, File file, String indexName)
      throws IOException {
    BulkRequest bulkRequest = new BulkRequest();
    GigawordFileProcessor proc = new GigawordFileProcessor(file);
    Iterator<Article> iterator = proc.iterator();

    // divide iterator to send a bounded number of bulk requests.
    UnmodifiableIterator<List<Article>> partitions = Iterators.partition(iterator, 100);
    while (partitions.hasNext()) {
      List<Article> articles = partitions.next();
      for (Article article : articles) {
        bulkRequest.add(
            new IndexRequest(indexName, "doc", article.getId()).source("text", article.getText()));
      }

      BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
      if (bulkResponse.hasFailures()) {
        log.error(bulkResponse.buildFailureMessage());
      }
    }
  }

  public static void queryAll(RestHighLevelClient client, String indexName) throws IOException {
    SearchRequest searchRequest = new SearchRequest();
    searchRequest.indices(indexName);
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(QueryBuilders.matchAllQuery());
    searchRequest.source(searchSourceBuilder);
    SearchResponse sr = client.search(searchRequest, RequestOptions.DEFAULT);
    SearchHits hits = sr.getHits();
    for (SearchHit hit : hits.getHits()) {
      log.info(hit.toString());
    }
  }

  public static void deleteIndex(RestHighLevelClient client, String indexName) throws IOException {
    DeleteIndexRequest deleteRequest = new DeleteIndexRequest(indexName);
    client.indices().delete(deleteRequest, RequestOptions.DEFAULT);
  }
}

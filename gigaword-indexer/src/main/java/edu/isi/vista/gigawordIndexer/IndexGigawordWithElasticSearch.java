package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
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
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
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

    if (argv.length > 0) {
      parameters = Parameters.loadSerifStyle(new File(argv[0]));
    } else {
      log.error("Expected one argument, a parameter file.");
      System.exit(1);
    }

    try {

      RestHighLevelClient client =
          new RestHighLevelClient(
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

      String indexName = parameters.getString(PARAM_INDEX_NAME);

      if (parameters.isPresent(PARAM_GIGAWORD_DIRECTORY_PATH)) {
        File gigawordDir = parameters.getExistingDirectory(PARAM_GIGAWORD_DIRECTORY_PATH);
        for (File concatenatedFileToIndex : gigawordDir.listFiles()) {
          if (concatenatedFileToIndex.isFile() && isValidGzipFile(concatenatedFileToIndex)) {
            index(client, concatenatedFileToIndex, indexName);
          }
        }
      } else {
        File concatenatedFileToIndex = parameters.getExistingFile(PARAM_GIGAWORD_FILEPATH);
        if (!isValidGzipFile(concatenatedFileToIndex)) {
        	client.close();
        	throw new Exception(concatenatedFileToIndex.getAbsolutePath() + " is not a valid GZip file");
        }
        index(client, concatenatedFileToIndex, indexName);
      }

      client.close();

    } catch (Exception e) {
      log.error("Caught exception: {}", e);
      System.exit(1);
    }
  }

  public static boolean isValidGzipFile(File file) throws IOException {
    // check file is a gzip file
    String contentType = Files.probeContentType(file.toPath());
    if ((contentType != null && !contentType.equalsIgnoreCase("application/gzip"))
        || (contentType == null
            && !FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("gz"))) {
      return false;
    }
    return true;
  }

  public static void index(RestHighLevelClient client, File file, String indexName)
      throws Exception {

    GigawordFileProcessor proc = new GigawordFileProcessor(file);
    Iterator<Article> iterator = proc.iterator();

    // Partition the iterator to size of 100
    UnmodifiableIterator<List<Article>> partitions = Iterators.partition(iterator, 100);
    while (partitions.hasNext()) {
      List<Article> articles = partitions.next();
      BulkRequest bulkRequest = new BulkRequest();
      for (Article article : articles) {
        XContentBuilder sourceBuilder =
            buildSourceObject(article, "en", "", new Date().toString(), "");
        bulkRequest.add(
            new IndexRequest(indexName, "texts", article.getId()).source(sourceBuilder));
      }

      BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
      if (bulkResponse.hasFailures()) {
        throw new Exception(bulkResponse.buildFailureMessage());
      }
    }
  }

  private static XContentBuilder buildSourceObject(
      Article article, String language, String source, String timestamp, String uri)
      throws IOException {

    XContentBuilder builder =
        XContentFactory.jsonBuilder()
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
    return builder;
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

package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.vista.gigawordIndexer.GigawordFileProcessor.Article;

public class IndexGigawordWithElasticSearch {

  private static final Logger log = LoggerFactory.getLogger(IndexGigawordWithElasticSearch.class);

  /**
   * @param argv takes two arguments, a path to the GZip file and an index name
   * @throws IOException
   */
  public static void main(String[] argv) throws IOException {

    // get arguments: gzip file path and index name
    if (argv.length < 2) {
      log.error("Required inputs: file path to GZip file and index name");
      System.exit(-1);
    }

    String filePath = argv[0];
    String indexName = argv[1];

    // check filePath is valid
    File file;
    if (!(file = new File(filePath)).exists()) {
      log.error("{} does not exist", filePath);
      System.exit(-1);
    }
    String contentType = Files.probeContentType(file.toPath());
    if ((contentType != null && !contentType.equalsIgnoreCase("application/gzip"))
        || (contentType == null
            && !FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("gz"))) {
      log.error("{} is not a valid GZip file", filePath);
      System.exit(-1);
    }

    // start indexing
    try {

      RestHighLevelClient client =
          new RestHighLevelClient(
              RestClient.builder(
                  new HttpHost("localhost", 9200, "http"),
                  new HttpHost("localhost", 9201, "http")));

      BulkRequest bulkRequest = new BulkRequest();
      GigawordFileProcessor proc = new GigawordFileProcessor(file);
      Iterator<Article> iterator = proc.iterator();
      while (iterator.hasNext()) {
        Article article = iterator.next();

        bulkRequest.add(
            new IndexRequest(indexName, "doc", article.getId()).source("text", article.getText()));
      }

      client.bulk(bulkRequest, RequestOptions.DEFAULT);

      client.close();
      client = null;

    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
      System.exit(-1);
    }
  }
}

package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.ImmutableMap;
import edu.isi.nlp.parameters.Parameters;
import org.apache.http.HttpHost;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class IndexGigawordWithElasticSearch {
    private static final ImmutableMap<String, String> sampleDocs = ImmutableMap.of(
            "doc1", "Hello world",
            "doc2", "la la la",
            "doc3", "The music-room in the Governor’s House at Port Mahon, a tall, handsome," +
                    " pillared octagon, was filled with the triumphant first movement of Locatelli’s C major quartet."
    );

    public static void main(String[] argv) throws IOException {
        final Parameters parameters;

        if (argv.length > 0) {
            parameters = Parameters.loadSerifStyle(new File(argv[0]));
        } else {
            parameters = Parameters.fromMap(ImmutableMap.of());
        }

        try (RestHighLevelClient client = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(parameters.getOptionalString("primaryHostName").or("localhost"),
                                parameters.getOptionalPositiveInteger("primaryPort").or(9200),
                                "http"),
                        new HttpHost(parameters.getOptionalString("secondaryHostName").or("localhost"),
                                parameters.getOptionalPositiveInteger("secondaryPort").or(9201),
                                "http")
                ))) {
            for (Map.Entry<String, String> e : sampleDocs.entrySet()) {
                final String docId = e.getKey();
                final String docContent = e.getValue();

                // you can index documents one-by-one
                client.index(new IndexRequest("documents", "doc", docId)
                        .source("text", docContent));
            }

            // TODO: in practice, you will want to use BulkRequest for speed
        }
    }
}

package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableMap;

import edu.isi.nlp.io.GZIPByteSource;

public class GigawordParserUtils {

  // start of a new document
  private static Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");

  // end of a document
  private static final String END_OF_DOCUMENT_MARKER = "</DOC>";

  public GigawordParserUtils() {}

  /**
   * split full text into separate articles
   *
   * @param fullText text to be split
   * @return A ImmutableMap of document ID and article
   * @throws InvalidFormatException
   */
  private static ImmutableMap<String, String> splitTextIntoArticles(String fullText)
      throws InvalidFormatException {

    ImmutableMap.Builder<String, String> map = new ImmutableMap.Builder<String, String>();
    int startNextSearchAt = 0;

    while (startNextSearchAt < fullText.length()) {

      // index of next end of document line start
      int endOfNextDocumentClosingElement =
          fullText.indexOf(END_OF_DOCUMENT_MARKER, startNextSearchAt);

      // while there is are documents to parse
      if (endOfNextDocumentClosingElement >= 0) {

        // index of the end of this document
        final int endOfDoc = endOfNextDocumentClosingElement + END_OF_DOCUMENT_MARKER.length();

        // string from start of doc to end of doc
        final String docString = fullText.substring(startNextSearchAt, endOfDoc);

        // update next search index
        startNextSearchAt = endOfDoc + 1;

        // find document id
        Matcher m =
            GIGAWORD_DOC_ELEMENT_PATTERN.matcher(
                docString.substring(0, Math.min(100, docString.length())));
        if (m.find()) {
          String docId = m.group(1);
          map.put(docId, docString);
        } else {
          throw new InvalidFormatException("Missing document ID");
        }
      }
    }
    return map.build();
  }

  /**
   * Get articles from a single gZip file
   *
   * @param file A GZip file
   * @return An ImmutableMap of article id and article
   * @throws FileNotFoundException
   * @throws IOException
   * @throws InvalidFormatException
   */
  public static ImmutableMap<String, String> getArticlesFromGZipFile(File file)
      throws FileNotFoundException, IOException, InvalidFormatException {
    String fullText =
        GZIPByteSource.fromCompressed(file).asCharSource(StandardCharsets.UTF_8).read();
    return splitTextIntoArticles(fullText);
  }
}

package edu.isi.vista.gigawordIndexer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FilenameUtils;

public class GigawordParserUtils {

  // start of a new document
  private static Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");

  // end of a document
  private static final String END_OF_DOCUMENT_MARKER = "</DOC>";

  public GigawordParserUtils() {}

  /**
   * Parse the full text from a GZip file
   *
   * @param filePath
   * @return String full text
   * @throws FileNotFoundException
   * @throws IOException
   */
  private static String gZipFileToString(String filePath)
      throws FileNotFoundException, IOException {
    StringBuilder fullText = new StringBuilder();
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new GZIPInputStream(new FileInputStream(filePath))));

    // first line
    String line = reader.readLine();
    fullText.append(line);

    while ((line = reader.readLine()) != null) {
      fullText.append("\n" + line);
    }
    reader.close();
    return fullText.toString();
  }

  /**
   * split full text into separate articles
   *
   * @param fullText
   * @return A HashMap of document ID and article
   */
  private static HashMap<String, String> splitTextIntoArticles(String fullText) {

    HashMap<String, String> result = new HashMap<String, String>();
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
        m.find();
        String docId = m.group(1);

        result.put(docId, docString);
      }
    }
    return result;
  }

  /**
   * Get articles from a single gZip file
   *
   * @param filePath
   * @return HashMap of article id and article
   * @throws FileNotFoundException
   * @throws IOException
   */
  public static HashMap<String, String> getArticlesFromGZipFile(String filePath)
      throws FileNotFoundException, IOException {
    String fullText = gZipFileToString(filePath);
    HashMap<String, String> articles = splitTextIntoArticles(fullText);
    return articles;
  }

  public static void main(String[] args) {
    String filePath = "/Users/jenniferchen/Downloads/gigaword_eng_5/data/afp_eng/afp_eng_199405.gz";
    try {
      String fullText = gZipFileToString(filePath);
      HashMap<String, String> articles = splitTextIntoArticles(fullText);
      Iterator<String> itr = articles.keySet().iterator();
      String docId1 = itr.next();
      System.out.println(articles.get(docId1));
    } catch (IOException e) {

      e.printStackTrace();
    }
  }
}

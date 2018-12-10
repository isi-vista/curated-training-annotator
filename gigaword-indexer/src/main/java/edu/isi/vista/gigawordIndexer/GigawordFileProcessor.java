package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.AbstractIterator;

import edu.isi.nlp.io.GZIPByteSource;

public class GigawordFileProcessor {

  private File file;

  private String fullText = null;

  public GigawordFileProcessor(File file) throws IOException {
    this.file = file;
  }

  public Iterator<Article> iterator() throws IOException {
    if (fullText == null) {
      fullText = GZIPByteSource.fromCompressed(file).asCharSource(StandardCharsets.UTF_8).read();
    }
    return new ArticlesIterator(fullText);
  }

  public class Article {

    private String id;

    private String text;

    public Article(String id, String text) {
      this.id = id;
      this.text = text;
    }

    public String getId() {
      return id;
    }

    public String getText() {
      return text;
    }

    @Override
    public String toString() {
      return "Article [id=" + id + ", text=" + text.substring(0, Math.min(100, text.length()-1)) + "]";
    }
  }

  private class ArticlesIterator extends AbstractIterator<Article> {

    // start of a new document
    private Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");

    // end of a document
    private final String END_OF_DOCUMENT_MARKER = "</DOC>";

    private String fullText;

    private int startNextSearchAt = 0;

    private ArticlesIterator(String text) {
      fullText = text;
    }

    @Override
    protected Article computeNext() {

      if (startNextSearchAt >= fullText.length()) {
        return endOfData();
      }

      // index of next end of document line start
      int endOfNextDocumentClosingElement =
          fullText.indexOf(END_OF_DOCUMENT_MARKER, startNextSearchAt);

      // Parse next document text
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
          return new Article(docId, docString);
        } else {
        	throw new RuntimeException("Missing document ID on article {} ");
        }
      }
      return endOfData();
    }
  }
}

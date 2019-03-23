package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import edu.isi.nlp.io.GZIPByteSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The LDC distributes Gigaword as a moderate number of gzipped files, each of which has many documents
 * concatenated together.  This class lets you iterate over the documents stored in such a file.
 */

public class ConcatenatedGigawordDocuments implements Iterable<Article> {
  private final String concatenatedFileText;

  private ConcatenatedGigawordDocuments(String concatenatedFileText) {
    this.concatenatedFileText = Objects.requireNonNull(concatenatedFileText);
  }

  public static ConcatenatedGigawordDocuments fromGigwordGZippedFile(Path p) throws IOException {
    return new ConcatenatedGigawordDocuments(GZIPByteSource.fromCompressed(p.toFile())
            .asCharSource(StandardCharsets.UTF_8).read());
  }

  public Iterator<Article> iterator() {
    return new ArticlesIterator();
  }

  private class ArticlesIterator extends AbstractIterator<Article> {

    // start of a new document
    private Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");

    // end of a document
    private final String END_OF_DOCUMENT_MARKER = "</DOC>";

    private int startNextSearchAt = 0;

    @Override
    protected Article computeNext() {
      if (startNextSearchAt >= concatenatedFileText.length()) {
        return endOfData();
      }

      // index of next end of document line start
      int endOfNextDocumentClosingElement =
              concatenatedFileText.indexOf(END_OF_DOCUMENT_MARKER, startNextSearchAt);

      // Parse next document text
      if (endOfNextDocumentClosingElement >= 0) {

        // index of the end of this document
        final int endOfDoc = endOfNextDocumentClosingElement + END_OF_DOCUMENT_MARKER.length();

        // string from start of doc to end of doc
        final String docString = concatenatedFileText.substring(startNextSearchAt, endOfDoc);

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
          throw new RuntimeException("Missing document ID on article");
        }
      }
      return endOfData();
    }
  }
}

package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import de.tudarmstadt.ukp.dkpro.core.io.penntree.PennTreeUtils;
import edu.isi.nlp.io.GZIPByteSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The LDC distributes Gigaword as a moderate number of gzipped files, each of which has many documents
 * concatenated together.  This class lets you iterate over the documents stored in such a file.
 */

public class ConcatenatedGigawordDocuments implements Iterable<ConcatenatedGigawordDocuments.Article> {
  private final String concatenatedFileText;
  private List<Article> articleList;
  boolean annotated;

  private ConcatenatedGigawordDocuments(String concatenatedFileText) {
    this.concatenatedFileText = Objects.requireNonNull(concatenatedFileText);
    this.annotated = false;
  }

  private ConcatenatedGigawordDocuments(String concatenatedFileText, List<Article> aArticleList) {
    this.concatenatedFileText = Objects.requireNonNull(concatenatedFileText);
    this.articleList = aArticleList;
    this.annotated = true;
  }
  
  public static ConcatenatedGigawordDocuments fromGigwordGZippedFile(Path p) throws IOException {
    return new ConcatenatedGigawordDocuments(GZIPByteSource.fromCompressed(p.toFile())
            .asCharSource(StandardCharsets.UTF_8).read());
  }

  public static ConcatenatedGigawordDocuments fromAnnotatedGigwordGZippedFile(Path p) throws IOException {
    // Initialize Streams
    InputStream fileInputStream = new FileInputStream(p.toString());
    GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
    InputStreamReader inputStreamReader = new InputStreamReader(gzipInputStream);
    BufferedReader br = new BufferedReader(inputStreamReader);
    String sCurrentLine;
    
    // variables for start of a new document
    Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");
    String currentDocId = "";
    
    // variables for saving articles
    boolean headlineStarted = false;
    boolean textStarted = false;
    String docText = "";
    List<Article> articleList = new ArrayList<>();
    
    
    // read file
    while((sCurrentLine = br.readLine()) != null) {
      
      sCurrentLine = sCurrentLine.trim();
      
      // extract document ID
      if(sCurrentLine.startsWith("<DOC id="))
      {
        Matcher m = GIGAWORD_DOC_ELEMENT_PATTERN.matcher(sCurrentLine);
        if(m.find()) {
          currentDocId = m.group(1);
        }
        else
        {
          throw new RuntimeException("Missing document ID on article");
        }
      }
      
      // create article when end of document (</TEXT>) is reached
      if (sCurrentLine.equals("</TEXT>"))
      {
        articleList.add(new Article(currentDocId, docText));
        docText = "";
        textStarted = false;
      }
      
      if(sCurrentLine.equals("</HEADLINE>"))
      {
        docText += "\n\n";
        headlineStarted = false;
      }
      
      // read lines from document text
      if ((headlineStarted || textStarted) && !sCurrentLine.startsWith("<")) {
        if(sCurrentLine.equals(""))
        {
          docText += "\n";
        }
        else if (sCurrentLine.startsWith("("))
        {
          String parsedText = PennTreeUtils.toText(PennTreeUtils.parsePennTree(sCurrentLine));
          if(!parsedText.equals("null"))
          {
            docText += parsedText + " ";
          }
        }
        // sometimes there is one unannotated line in the document which can be used as plain text
        else
        {
          docText += sCurrentLine + " ";
        }
      }
      
      // start saving text when start of document (<TEXT>) is observed
      if (sCurrentLine.equals("<TEXT>")) {
        textStarted = true;
      }
      
      if (sCurrentLine.equals("<HEADLINE>")) {
        headlineStarted = true;
      }
    }
    
    return new ConcatenatedGigawordDocuments(docText, articleList);
  }
  
  public Iterator<Article> iterator() {
    if (annotated) {
      return new AnnotatedArticlesIterator();
    }
    else
    {
      return new ArticlesIterator();
    }
  }

  public static class Article {
    private String id;
    private String text;

    private Article(String id, String text) {
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
      return "Article [id="
          + id
          + ", text="
          + text.substring(0, Math.min(100, text.length() - 1))
          + "...]";
    }
  }

  private class AnnotatedArticlesIterator extends AbstractIterator<Article> {
    
    private int startNextIndex = 0;
    
    @Override
    protected Article computeNext() {
      
      if (startNextIndex >= articleList.size()) {
        return endOfData();
      }
      
      else
      {
        Article nextArticle = articleList.get(startNextIndex);
        startNextIndex ++;
        return nextArticle;
      }
    }
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

package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import edu.isi.nlp.corpora.LctlText;
import edu.isi.nlp.corpora.LtfDocument;
import edu.isi.nlp.corpora.LtfReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LTFDocuments implements Iterable<LTFDocuments.Article>{

  private static final Logger log = LoggerFactory.getLogger(LTFDocuments.class);

  public static void main(String[] args) {
    // test
    try {
      File file = new File("/Users/jenniferchen/Downloads/LDC2017E06_LORELEI_IL4_Incident_Language_Pack_V2.0/set0/data/monolingual_text/ltf/IL4_DF_020072_20030212_G0040FIVA.ltf.xml");
      LTFDocuments documents = LTFDocuments.fromLtfFile(file.toPath());
      Iterator<Article> iterator = documents.iterator();
      while(iterator.hasNext()) {
        Article article = iterator.next();
        log.debug(article.toString());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private LctlText lctlTexts;

  @Override
  public Iterator<Article> iterator() {
    return new ArticlesIterator();
  }

  private LTFDocuments(LctlText text) {
    lctlTexts = text;
  }

  public static LTFDocuments fromLtfFile(Path p) throws IOException {
    LctlText loadedLctlText = (new LtfReader()).read(Resources.asCharSource(p.toUri().toURL(), StandardCharsets.UTF_8));
    return new LTFDocuments(loadedLctlText);
  }

//  public static LTFDocuments fromLTFZippedFile(Path p) throws IOException {
//    List<LctlText> texts = new ArrayList<>();
//    LtfReader reader = new LtfReader();
//
//    ZipFile zipFile = new ZipFile(p.toFile(), StandardCharsets.UTF_8);
//    Enumeration<? extends ZipEntry> entries = zipFile.entries();
//    while(entries.hasMoreElements()) {
//      ZipEntry entry = entries.nextElement();
//      InputStream is = zipFile.getInputStream(entry);
//      texts.add(reader.read(new ZipEntryByteSource(is).asCharSource(StandardCharsets.UTF_8)));
//    }
//    return new LTFDocuments(texts);
//  }

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

  private class ArticlesIterator extends AbstractIterator<Article> {

    ImmutableList<LtfDocument> docs = lctlTexts.getDocuments();
    int index = 0;

    @Override
    protected Article computeNext() {
      if (index >= docs.size()) {
        return endOfData();
      }

      LtfDocument doc = docs.get(index);
      Article article = new Article("id", doc.getOriginalText().content().utf16CodeUnits());
      index++;
      return article;

    }
  }

}

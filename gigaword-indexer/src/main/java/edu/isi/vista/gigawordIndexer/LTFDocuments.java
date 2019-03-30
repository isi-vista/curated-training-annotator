package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import edu.isi.nlp.corpora.LctlText;
import edu.isi.nlp.corpora.LtfDocument;
import edu.isi.nlp.corpora.LtfReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LTFDocuments implements Iterable<Article>, Closeable {

  private static final Logger log = LoggerFactory.getLogger(LTFDocuments.class);

  private ZipFile zipFile;

  private Enumeration<? extends ZipEntry> ltfZipEntries;

  private ZipEntry entry; // current entry for tracking error

  @Override
  public void close() throws IOException {
    zipFile.close();
  }

  @Override
  public Iterator<Article> iterator() {
    return new ArticlesIterator();
  }

  private LTFDocuments(ZipFile zipFile) {
    this.zipFile = zipFile;
    this.ltfZipEntries = zipFile.entries();

  }

  public static LTFDocuments fromLTFZippedFile(Path p) throws IOException {

    ZipFile zipFile = new ZipFile(p.toFile(), StandardCharsets.UTF_8);
    return new LTFDocuments(zipFile);

  }


  private class ArticlesIterator extends AbstractIterator<Article> {
    private LtfReader reader = new LtfReader();
    private ImmutableList<LtfDocument> docs;
    private int index;

    private ArticlesIterator() {
      // get first zip entry of Ltf from zip file
      entry = nextLtfEntry();
    }

    private ZipEntry nextLtfEntry() {
      if (ltfZipEntries.hasMoreElements()) {
        ZipEntry entry = ltfZipEntries.nextElement();
        try (InputStream is = zipFile.getInputStream(entry)) {
          byte[] bytes = IOUtils.toByteArray(is);
          if (entry.getName().endsWith(".ltf.xml")) {
            LctlText lctlText =
                reader.read(ByteSource.wrap(bytes).asCharSource(StandardCharsets.UTF_8));
            docs = lctlText.getDocuments();
            index = 0;
            return entry;
          } else {
            return nextLtfEntry();
          }
        } catch (IOException e) {
          log.error("Caught exception: {}", e);
          System.exit(1);
        }
      }
      return null; // no more entries
    }

    @Override
    protected Article computeNext() {
      if (index >= docs.size()) {
        if (nextLtfEntry() == null) {
          return endOfData();
        }
      }

      LtfDocument doc = docs.get(index);

      Article article;
      try {
          article = new Article(doc.getId(), doc.getOriginalText().content().utf16CodeUnits(), doc.getSegments().size());
      } catch (Exception e) {
        // avoid crash due to checksum error
        log.error("Exception getting document content: {}", entry.getName());
        log.error(e.getMessage());
        article = Article.failedArticle(doc.getId(), "");
      }
      index++;
      return article;

    }
  }

}

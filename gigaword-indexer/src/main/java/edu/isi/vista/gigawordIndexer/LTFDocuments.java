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
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LTFDocuments implements Iterable<Article>, Closeable {

  private static final Logger log = LoggerFactory.getLogger(LTFDocuments.class);

  private ZipFile zipFile;

  private Enumeration<? extends ZipEntry> ltfZipEntries;


  @Override
  public void close() throws IOException {
    zipFile.close();
  }

  @NotNull
  @Override
  public Iterator<Article> iterator() {
    return new ArticlesIterator();
  }

  private LTFDocuments(ZipFile zipFile) {
    this.zipFile = zipFile;
    this.ltfZipEntries = zipFile.entries();

  }

  static LTFDocuments fromLTFZippedFile(Path p) throws IOException {

    ZipFile zipFile = new ZipFile(p.toFile(), StandardCharsets.UTF_8);
    return new LTFDocuments(zipFile);

  }


  private class ArticlesIterator extends AbstractIterator<Article> {
    private LtfReader reader = new LtfReader();
    private ZipEntry currentLtfEntry;
    private ImmutableList<LtfDocument> entryDocs;
    private int docIndex;

    private ArticlesIterator() {
      // get first zip entry of Ltf from zip file
      Optional<ZipEntry> entry = nextLtfEntry();
      if (entry.isPresent()) {
        currentLtfEntry = entry.get();
        try {
          entryDocs = getDocsForLtfEntry(currentLtfEntry);
          docIndex = 0;
        } catch (IOException e) {
          log.error("Error reading zip file entry: {} in {}", currentLtfEntry.getName(), zipFile.getName());
          log.error(e.getMessage());
          throw new RuntimeException(e);
        }
      } else {
        throw new RuntimeException("Zip file " + zipFile.getName() + " has no entries");
      }
    }

    private Optional<ZipEntry> nextLtfEntry() {
      Optional<ZipEntry> result = Optional.empty();
      while (ltfZipEntries.hasMoreElements()) {
        ZipEntry entry = ltfZipEntries.nextElement();
        if (entry.getName().endsWith(".ltf.xml")) {
          result = Optional.of(entry);
          break;
        }
      }
      return result;
    }

    private ImmutableList<LtfDocument> getDocsForLtfEntry(ZipEntry entry) throws IOException {
      try (InputStream is = zipFile.getInputStream(entry)) {
        byte[] bytes = IOUtils.toByteArray(is);
        LctlText lctlText =
            reader.read(ByteSource.wrap(bytes).asCharSource(StandardCharsets.UTF_8));
        return lctlText.getDocuments();
      }
    }

    @Override
    protected Article computeNext() {
      if (docIndex >= entryDocs.size()) { // done with this entry
        if (!nextLtfEntry().isPresent()) {
          return endOfData();
        } else {
          try {
            currentLtfEntry = nextLtfEntry().get();
            entryDocs = getDocsForLtfEntry(currentLtfEntry);
            docIndex = 0;
          } catch (IOException e) {
            log.error("Error reading zip file entry: {} in {}", currentLtfEntry.getName(), zipFile.getName());
            log.error(e.getMessage());
            throw new RuntimeException(e);
          }
        }
      }

      LtfDocument doc = entryDocs.get(docIndex);

      Article article;
      try {
          article = new Article(doc.getId(), doc.getOriginalText().content().utf16CodeUnits(), doc.getSegments().size());
      } catch (RuntimeException e) {
        // avoid crash due to checksum error
        log.error("Exception getting document content: {}", currentLtfEntry.getName());
        log.error(e.getMessage());
        article = Article.failedArticle(doc.getId(), "");
      }
      docIndex++;
      return article;

    }
  }

}

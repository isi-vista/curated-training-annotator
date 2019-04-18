package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import edu.isi.nlp.corpora.LctlText;
import edu.isi.nlp.corpora.LtfDocument;
import edu.isi.nlp.corpora.LtfReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LTFDocuments implements ArticleSource {
  private static final Logger log = LoggerFactory.getLogger(LTFDocuments.class);

  private ZipFile zipFile;

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
  }

  static LTFDocuments fromLTFZippedFile(Path p) throws IOException {
    return new LTFDocuments(new ZipFile(p.toFile(), StandardCharsets.UTF_8));
  }


  private class ArticlesIterator extends AbstractIterator<Article>
  {
    private LtfReader reader = new LtfReader();
    // we iterate at two levels here.
    // At the top level, we iterate over entries in the zip file
    // which correspond to LTF XML files
    private Iterator<? extends ZipEntry> ltfZipEntries = zipFile.stream()
            .filter(
                    entry -> entry.getName() != null && entry.getName().endsWith(".ltf.xml"))
            .iterator();

    // at the next level, we iterate over ltf documents within each LTF XML file
    // (usually there is only one, but in theory there could be multiple)
    private Iterator<Article> articlesInLtfDocIterator = ImmutableList.<Article>of().iterator();

    private ArticlesIterator() { }

    @Override
    protected Article computeNext() {
      try {
        // if we run out of documents in the current zip file entry...
        while (!articlesInLtfDocIterator.hasNext()) {
          // we should move on to the next zip entry...
          if (ltfZipEntries.hasNext()) {
            articlesInLtfDocIterator = articlesForLtfEntry(ltfZipEntries.next());
          } else {
            // and if we are out of zip entries, we are out of documents.
            return endOfData();
          }
        }

        return articlesInLtfDocIterator.next();
      } catch (IOException ioe) {
        throw new RuntimeException("Exception loading LTFs", ioe);
      }
    }

    private Iterator<Article> articlesForLtfEntry(ZipEntry entry) throws IOException {
      try (InputStream is = zipFile.getInputStream(entry)) {
        byte[] bytes = IOUtils.toByteArray(is);
        try {
          LctlText lctlText = reader.read(ByteSource.wrap(bytes).asCharSource(StandardCharsets.UTF_8));
          return lctlText.getDocuments().stream()
                  .map(
                          doc -> new Article(doc.getId(),
                                  doc.getOriginalText().content().utf16CodeUnits(),
                                  doc.getSegments().size())).iterator();
        } catch (Exception e) {
            return ImmutableList.of(Article.failedArticle("failed", "failed")).iterator();
        }
      }
    }
  }
}

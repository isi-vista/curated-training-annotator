package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Single ACE Document as an article source
 */
public class AceDocument implements ArticleSource {
    final private String docText;

    private AceDocument(String fileText) {
        this.docText = Objects.requireNonNull(fileText);
    }

    public static AceDocument AceDocumentFromPath(Path filePath) throws IOException {
        return new AceDocument(new String(Files.readAllBytes(filePath)));
    }

    public Iterator<Article> iterator() {
        return new ArticleIterator();
    }

    @Override
    public void close() throws Exception
    {

    }

    private class ArticleIterator extends AbstractIterator<Article> {
        // This is to prevent the iterator from iterating infinitely
        boolean firstCompute = false;
        protected Article computeNext(){
            if (firstCompute) {
                return endOfData();
            }
            // DOC ID marker
            Pattern ACE_DOC_ID_PATTERN = Pattern.compile("<DOCID> (.*?) </DOCID>");

            Matcher m =
                    ACE_DOC_ID_PATTERN.matcher(
                            docText.substring(0, Math.min(120, docText.length())));
            if (m.find()) {
                String docId = m.group(1);
                String processedDocText = docText.replaceAll("\\r\\n", "\n");
                firstCompute = true;
                return new Article(docId, processedDocText);
            } else {
                throw new RuntimeException("Missing document ID on article");
            }
        }
    }
}

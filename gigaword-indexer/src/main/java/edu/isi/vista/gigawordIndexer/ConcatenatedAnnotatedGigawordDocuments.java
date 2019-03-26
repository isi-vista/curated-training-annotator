package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The LDC distributes annotated Gigaword as a moderate number of gzipped files, each of which has many documents
 * concatenated together. This class lets you iterate over the documents stored in such a file. This class was
 * authored by the UKP Lab of Technische Universität Darmstadt and is included here for their convenience.
 */

public class ConcatenatedAnnotatedGigawordDocuments implements Iterable<Article> {
    private List<Article> articleList;
    
    private ConcatenatedAnnotatedGigawordDocuments(List<Article> aArticleList) {
        this.articleList = aArticleList;
    }
    
    public static ConcatenatedAnnotatedGigawordDocuments fromAnnotatedGigwordGZippedFile(Path p) throws Exception {
        // Initialize Streams
        try (InputStream fileInputStream = new FileInputStream(p.toString());
             GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream)) {
            
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            AnnotatedGigawordParser parser = new AnnotatedGigawordParser();
            saxParser.parse(gzipInputStream, parser);

            return new ConcatenatedAnnotatedGigawordDocuments(parser.getArticleList());
        }
    }
    
    public Iterator<Article> iterator() {
        return new AnnotatedArticlesIterator();
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
}

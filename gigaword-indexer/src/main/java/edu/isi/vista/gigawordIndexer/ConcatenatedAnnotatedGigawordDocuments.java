package edu.isi.vista.gigawordIndexer;

import com.google.common.collect.AbstractIterator;
import de.tudarmstadt.ukp.dkpro.core.io.penntree.PennTreeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The LDC distributes annotated Gigaword as a moderate number of gzipped files, each of which has many documents
 * concatenated together. This class lets you iterate over the documents stored in such a file.
 * This class does not preserve the offsets from the original document texts and therefore cannot be used for
 * the curated training process. This class was authored by the UKP Lab of Technische Universität Darmstadt and
 * is included here for their convenience.
 */

public class ConcatenatedAnnotatedGigawordDocuments implements Iterable<Article> {
    private List<Article> articleList;
    
    private ConcatenatedAnnotatedGigawordDocuments(List<Article> aArticleList) {
        this.articleList = aArticleList;
    }
    
    public static ConcatenatedAnnotatedGigawordDocuments fromAnnotatedGigwordGZippedFile(Path p) throws IOException {
        // Initialize Streams
        try (InputStream fileInputStream = new FileInputStream(p.toString());
             GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
             InputStreamReader inputStreamReader = new InputStreamReader(gzipInputStream);
             BufferedReader br = new BufferedReader(inputStreamReader)) {
            
            String sCurrentLine;
            
            // variables for start of a new document
            Pattern GIGAWORD_DOC_ELEMENT_PATTERN = Pattern.compile("<DOC id=\"(.*?)\".*>");
            String currentDocId = "";
            
            // variables for saving articles
            boolean headlineStarted = false;
            boolean textStarted = false;
            StringBuilder docText = new StringBuilder();
            List<Article> articleList = new ArrayList<>();
            
            
            // read file
            while ((sCurrentLine = br.readLine()) != null) {
                
                sCurrentLine = sCurrentLine.trim();
                
                // extract document ID
                if (sCurrentLine.startsWith("<DOC id=")) {
                    Matcher m = GIGAWORD_DOC_ELEMENT_PATTERN.matcher(sCurrentLine);
                    if (m.find()) {
                        currentDocId = m.group(1);
                    }
                    else {
                        throw new RuntimeException("Missing document ID on article");
                    }
                }
                
                // create article when end of document (</TEXT>) is reached
                if (sCurrentLine.equals("</TEXT>")) {
                    articleList.add(new Article(currentDocId, docText.toString().trim()));
                    docText = new StringBuilder();
                    textStarted = false;
                }
                
                if (sCurrentLine.equals("</HEADLINE>")) {
                    docText.append("\n\n");
                    headlineStarted = false;
                }
                
                // read lines from document text
                if ((headlineStarted || textStarted) && !sCurrentLine.startsWith("<")) {
                    if (sCurrentLine.equals("")) {
                        docText.append("\n");
                    }
                    else if (sCurrentLine.startsWith("(")) {
                        String parsedText = PennTreeUtils.toText(PennTreeUtils.parsePennTree(sCurrentLine));
                        if (!parsedText.equals("null")) {
                            docText.append(parsedText).append(" ");
                        }
                    }
                    // sometimes there is one unannotated line in the document which can be used as plain text
                    else {
                        docText.append(sCurrentLine).append(" ");
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
            
            return new ConcatenatedAnnotatedGigawordDocuments(articleList);
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

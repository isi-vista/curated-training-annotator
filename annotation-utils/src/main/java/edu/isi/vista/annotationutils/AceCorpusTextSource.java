package edu.isi.vista.annotationutils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import edu.isi.nlp.symbols.Symbol;
import com.google.common.base.Optional;
import edu.isi.nlp.io.OriginalTextSource;
import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Arrays;


public final class AceCorpusTextSource implements OriginalTextSource{
    private  File aceDataDirectory;
    private List<File> sourceFileDirectories;

    public Optional<String> getOriginalText(final Symbol docID) throws IOException {
        Optional<String> rawSourceText = Optional.absent();
        // Search and find file in the source directories
        for (File sourceDir : sourceFileDirectories){
            File sourceDocFile = new File(sourceDir, docID.toString() + ".sgm");
            if(sourceDocFile.exists()) {
                rawSourceText =
                        Optional.of(Files.asCharSource(sourceDocFile, Charsets.UTF_8).read());
                break;
            }
        }
        if(!rawSourceText.isPresent()) {
            throw new IOException("Source file is empty for document: " + docID.toString());
        }
        return Optional.of(processAceSourceText(rawSourceText.get()));
    }

    AceCorpusTextSource(File aceDataDirectory) throws IOException{
        if(!aceDataDirectory.exists()) {
            throw new IOException("The aceDataDirectory does not exist");
        }
        if(!aceDataDirectory.isDirectory()) {
            throw new IOException("The aceDataDirectory parameter given is not a directory");
        }
        this.aceDataDirectory = aceDataDirectory;
        this.sourceFileDirectories = Arrays.asList(aceDataDirectory.listFiles());
        for(int i = 0; i < this.sourceFileDirectories.size(); i++){
            this.sourceFileDirectories.set(i, new File(this.sourceFileDirectories.get(i), "adj"));
        }
    }

    private String processAceSourceText (String rawSourceText) {
        // The ace corpus annotation offests treat newlines as a single character (so \r\n is
        // changed to \n) and do not count tags (so all tags '<>' and it's enclosing content is
        // removed)
        return rawSourceText
                .replaceAll("\\r\\n", "\n")
                .replaceAll("<[\\s\\S]*?>", "");
    }
}

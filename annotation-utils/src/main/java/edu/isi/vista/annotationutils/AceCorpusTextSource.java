package edu.isi.vista.annotationutils;

import edu.isi.nlp.symbols.Symbol;
import com.google.common.base.Optional;
import edu.isi.nlp.io.OriginalTextSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;


public final class AceCorpusTextSource implements OriginalTextSource{
    private  File aceDataDirectory;
    private File[] sourceFileDirectories;

    public Optional<String> getOriginalText(final Symbol docID) throws IOException {
        String rawSourceText = "";
        // Search and find file in the source directories
        for (File sourceDir : sourceFileDirectories){
            File sourceDocFile = new File(sourceDir, docID.toString() + ".sgm");
            if(sourceDocFile.exists()) {
                rawSourceText = Files.readString(sourceDocFile.toPath());
                break;
            }
        }
        if(rawSourceText.isEmpty()) {
            throw new IOException("Source file is empty for document: " + docID.toString());
        }
        return Optional.of(processAceSourceText(rawSourceText));
    }

    AceCorpusTextSource(File aceDataDirectory) throws IOException{
        if(!aceDataDirectory.exists()) {
            throw new IOException("The aceDataDirectory does not exist");
        }
        if(!aceDataDirectory.isDirectory()) {
            throw new IOException("The aceDataDirectory parameter given is not a directory");
        }
        this.aceDataDirectory = aceDataDirectory;
        this.sourceFileDirectories = aceDataDirectory.listFiles();
        for(int i = 0; i < this.sourceFileDirectories.length; i++) {
            this.sourceFileDirectories[i] = new File(sourceFileDirectories[i], "adj");
        }
    }

    private String processAceSourceText (String rawSourceText) {
        return rawSourceText
                .replaceAll("\\r\\n", "\n")
                .replaceAll("<[\\s\\S]*?>", "");
    }
}

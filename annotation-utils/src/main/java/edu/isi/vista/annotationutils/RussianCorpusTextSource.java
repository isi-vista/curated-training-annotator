package edu.isi.vista.annotationutils;

import com.google.common.base.Charsets;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import edu.isi.nlp.corpora.LctlText;
import edu.isi.nlp.corpora.LtfDocument;
import edu.isi.nlp.corpora.LtfReader;
import edu.isi.nlp.symbols.Symbol;
import com.google.common.base.Optional;
import edu.isi.nlp.io.OriginalTextSource;
import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;


public final class RussianCorpusTextSource implements OriginalTextSource{
    private  File russianDataDirectory;

    public Optional<String> getOriginalText(final Symbol docID) throws IOException {
        Optional<CharSource> russianCharSource = Optional.absent();
        // Search and find file in unzipped/ltf/
        File sourceDocFile = new File(
                russianDataDirectory + "/data/monolingual_text/unzipped/ltf/" + docID.toString() + ".ltf.xml"
        );
        if(sourceDocFile.exists()) {
            russianCharSource =
                    Optional.of(Files.asCharSource(sourceDocFile, Charsets.UTF_8));
        }
        if(!russianCharSource.isPresent()) {
            throw new IOException("Source file is empty for document: " + docID.toString());
        }
        return Optional.of(processRussianSourceText(russianCharSource.get()));
    }

    RussianCorpusTextSource(File russianDataDirectory) throws IOException{
        if(!russianDataDirectory.exists()) {
            throw new IOException("The russianDataDirectory does not exist");
        }
        if(!russianDataDirectory.isDirectory()) {
            throw new IOException("The russianDataDirectory parameter given is not a directory");
        }
        this.russianDataDirectory = russianDataDirectory;
    }

    private String processRussianSourceText (CharSource russianCharSource) {
        // In theory there could be multiple documents within a single .ltf.xml file,
        // so for now we are stringing together the original text
        // of each document - even if there is only ever one - in the corpus file.
        LtfReader reader = new LtfReader();
        List<String> originalTextItems = new ArrayList<>();
        LctlText lctlText = reader.read(russianCharSource);
        List<LtfDocument> ltfDocuments = lctlText.getDocuments();
        for (LtfDocument ltfDocument : ltfDocuments) {
            originalTextItems.add(ltfDocument.getOriginalText().content().toString());
        }
        return String.join("\n", originalTextItems);
    }
}


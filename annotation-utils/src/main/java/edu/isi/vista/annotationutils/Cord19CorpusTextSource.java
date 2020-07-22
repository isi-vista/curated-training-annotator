package edu.isi.vista.annotationutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.io.Files;
import edu.isi.nlp.io.OriginalTextSource;
import edu.isi.nlp.symbols.Symbol;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;


public final class Cord19CorpusTextSource implements OriginalTextSource{
    private  File cord19DataDirectory;
    private List<File> sourceFileDirectories;

    public Optional<String> getOriginalText(final Symbol docID) throws IOException {
        Optional<String> rawSourceText = Optional.absent();
        // Search and find file in the source directories
        for (File sourceDir : sourceFileDirectories){
            File sourceDocFile = new File(sourceDir, docID.toString() + ".json");
            if(sourceDocFile.exists()) {
                rawSourceText =
                        Optional.of(Files.asCharSource(sourceDocFile, Charsets.UTF_8).read());
                break;
            }
        }
        if(!rawSourceText.isPresent()) {
            throw new IOException("Source file is empty for document: " + docID.toString());
        }
        return Optional.of(processCord19SourceText(rawSourceText.get()));
    }

    Cord19CorpusTextSource(File cord19DataDirectory) throws IOException{
        if(!cord19DataDirectory.exists()) {
            throw new IOException("The cord19DataDirectory does not exist");
        }
        if(!cord19DataDirectory.isDirectory()) {
            throw new IOException("The cord19DataDirectory parameter given is not a directory");
        }
        this.cord19DataDirectory = cord19DataDirectory;
        // Directory structure is
        // <data-directory>/<folder-name-x>/<folder-name-x>/
        this.sourceFileDirectories = Arrays.asList(
                new File(this.cord19DataDirectory + "/biorxiv_medrxiv/biorxiv_medrxiv"),
                new File(this.cord19DataDirectory + "/comm_use_subset/comm_use_subset"),
                new File(this.cord19DataDirectory + "/noncomm_use_subset/noncomm_use_subset"),
                new File(this.cord19DataDirectory + "/pmc_custom_license/pmc_custom_license")
        );
    }

    private String processCord19SourceText (String rawSourceText) {
        final StringBuilder ret = new StringBuilder();
        final ObjectNode jsonTree;
        try {
            jsonTree = (ObjectNode)new ObjectMapper().readTree(rawSourceText);
        }
        catch (IOException e) {
            throw new RuntimeException("Could not read CORD-19 source text.");
        }

        // Title, then a newline
        String title = jsonTree.get("metadata").get("title").asText();
        ret.append(title);
        ret.append("\n\n");
        // Guard against duplicate section headers
        String lastSection = null;
        // Abstract section header, newline, abstract text, newline
        final JsonNode abstracts = jsonTree.get("abstract");
        for (JsonNode abstractNode : abstracts) {
            final String curSection = abstractNode.get("section").asText();
            if (!curSection.equals(lastSection)) {
                ret.append(curSection);
                ret.append("\n\n");
            }
            lastSection = curSection;
            ret.append(abstractNode.get("text").asText());
            ret.append("\n\n");
        }

        lastSection = null;
        for (JsonNode bodyTextNode : jsonTree.get("body_text")) {
            final String curSection = bodyTextNode.get("section").asText();
            if (!curSection.equals(lastSection)) {
                ret.append(curSection);
                ret.append("\n\n");
            }
            lastSection = curSection;
            ret.append(bodyTextNode.get("text").asText());
            ret.append("\n\n");
        }

        for (JsonNode refTextNode : jsonTree.get("ref_entries")) {
            ret.append(refTextNode.get("text").asText());
            ret.append("\n\n");
        }

        return ret.toString();
    }
}

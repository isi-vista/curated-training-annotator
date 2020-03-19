package edu.isi.vista.gigawordIndexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public class Covid19ArticleSource implements ArticleSource
{
    private Path documentDirectory;

    private Covid19ArticleSource(Path documentDirectory) {
        this.documentDirectory = documentDirectory;
    }

    public static Covid19ArticleSource fromDirectory(Path documentDirectory) {
        return new Covid19ArticleSource(documentDirectory);
    }

    @Override
    public Iterator<Article> iterator()
    {
        try {
            return Files.walk(this.documentDirectory).filter(x -> x.toString().endsWith("json"))
                    .map(x -> covidJsonToArticle(x)).iterator();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override public void close()
    {
        // nothing to do, since files are opened and closed by the Iterator
    }

    private Article covidJsonToArticle(Path covidJsonPath) {
        try {
            return covidJsonToRawText(com.google.common.io.Files.asCharSource(covidJsonPath.toFile(), StandardCharsets.UTF_8).read());
        } catch (Exception e) {
            throw new RuntimeException("Error while processing " + covidJsonPath, e);
        }
    }

    public Article covidJsonToRawText(String covidJsonContent) {
        final StringBuilder ret = new StringBuilder();

        final ObjectNode jsonTree;
        try {
            jsonTree = (ObjectNode)new ObjectMapper().readTree(covidJsonContent);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        // We currently do not translate authors or bibliography entries
        String paperId = jsonTree.get("paper_id").asText();

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


        final String rawText = ret.toString();
        return new Article(paperId, rawText);
    }
}


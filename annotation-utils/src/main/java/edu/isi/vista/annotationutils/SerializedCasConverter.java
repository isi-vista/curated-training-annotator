package edu.isi.vista.annotationutils;

import static org.apache.uima.cas.impl.Serialization.deserializeCASComplete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import edu.isi.nlp.parameters.Parameters;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.impl.CASCompleteSerializer;
import org.apache.uima.cas.impl.CASImpl;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.CasCreationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import webanno.custom.CTEventSpan;
import webanno.custom.CTEventSpanType;

public class SerializedCasConverter {

  private static final String USAGE =
      "SerializedCasConverter param_file\n"
          + "\tparam file consists of :-separated key-value pairs\n"
          + "\tThe required parameters are:\n"
          + "\tprojectName: the name of the project\n"
          + "\tinputDirectory: the path to a directory with serialized CAS files (.ser)\n"
          + "\toutputDirectory: the path to a directory where output files to be written to";

  private static final Logger log = LoggerFactory.getLogger(SerializedCasConverter.class);

  private static final String PARAM_PROJECT_NAME = "projectName";

  private static final String PARAM_INPUT_DIRECTORY = "inputDirectory";

  private static final String PARAM_OUTPUT_DIRECTORY = "outputDirectory";

  public static void main(String[] args) throws IOException {

    // get parameters from parameter file
    Parameters parameters = null;

    if (args.length == 1) {
      parameters = Parameters.loadSerifStyle(new File(args[0]));
    } else {
      System.err.println(USAGE);
      System.exit(1);
    }

    String projectName = parameters.getString(PARAM_PROJECT_NAME);
    File inputDir = new File(parameters.getString(PARAM_INPUT_DIRECTORY));
    File outputDir = new File(parameters.getString(PARAM_OUTPUT_DIRECTORY));

    if (!inputDir.exists() || !inputDir.isDirectory()) {
      log.error("Invalid input directory.");
      System.exit(1);
    }

    if (!outputDir.exists()) {
      outputDir.mkdirs();
    } else if (!outputDir.isDirectory()) {
      log.error("Invalid output directory");
      System.exit(1);
    }

    try {
      export(projectName, inputDir, outputDir);
    } catch (IOException | UIMAException e) {
      log.error("Caught exception {}", e);
      System.exit(1);
    }
  }

  private static void export(String projectName, File inputDir, File outputDir)
      throws IOException, UIMAException {
    for (File input : inputDir.listFiles()) {
      if (input.isDirectory()) {
        export(projectName, input, outputDir);
      } else if (input.getName().toLowerCase().endsWith(".ser")) {
        exportFile(projectName, input, outputDir);
      }
    }
  }

  private static void exportFile(String projectName, File input, File outputDir)
      throws IOException, UIMAException {

    CAS cas = readCasFromFile(input);
    JCas jcas = cas.getJCas();

    ImmutableListMultimap.Builder mapBuilder =
        new ImmutableListMultimap.Builder<CTEventSpan, Argument>();
    List<CTEventSpan> attachedEventSpans = new ArrayList<>();

    // event with arguments are found using relation annotation CTEventSpanType
    for (CTEventSpanType type : JCasUtil.select(jcas, CTEventSpanType.class)) {

      String role = type.getRelation_type();
      CTEventSpan event = type.getDependent();
      CTEventSpan argument = type.getGovernor();

      // mark spans as attached
      attachedEventSpans.add(event);
      attachedEventSpans.add(argument);

      mapBuilder.put(event, new Argument(role, argument.getBegin(), argument.getEnd()));
    }

    // Create CTEvent list
    ImmutableMap<CTEventSpan, Collection<Argument>> map = mapBuilder.build().asMap();
    List<CTEvent> events = new ArrayList<>();
    for (Map.Entry<CTEventSpan, Collection<Argument>> entry : map.entrySet()) {
      // create and add event
      CTEventSpan eventAnnotation = entry.getKey();
      events.add(
          new CTEvent(
              eventAnnotation.getBegin(),
              eventAnnotation.getEnd(),
              entry.getValue(),
              eventAnnotation.getNegative_example()));
    }

    // catch events with no arguments
    for (CTEventSpan event : JCasUtil.select(jcas, CTEventSpan.class)) {
      if (!attachedEventSpans.contains(event)) {
        events.add(
            new CTEvent(
                event.getBegin(),
                event.getEnd(),
                Collections.emptyList(),
                event.getNegative_example()));
      }
    }

    // Using document ID to name the output files
    // Temporary solution: Making the assumption document ID is present in the document text
    String text = jcas.getDocumentText();
    Pattern pattern = Pattern.compile("<DOC id=\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      String title = matcher.group(1);
      String id =
          projectName
              + "_"
              + title
              + "_"
              + FilenameUtils.removeExtension(input.getName())
              + ".json";
      ObjectMapper mapper = new ObjectMapper();
      try (OutputStream os = new FileOutputStream(new File(outputDir, id))) {
        mapper.writeValue(os, events);
      }
    } else {
      log.error("Missing document id: " + input.getAbsolutePath());
    }
  }

  private static CAS readCasFromFile(File serializedCasFile) throws IOException, UIMAException {
    if (!serializedCasFile.exists()) {
      throw new FileNotFoundException(serializedCasFile + " not found");
    }

    CAS cas = CasCreationUtils.createCas((TypeSystemDescription) null, null, null);
    readSerializedCas(cas, serializedCasFile);

    return cas;
  }

  private static void readSerializedCas(CAS aCas, File aFile) throws IOException {
    try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(aFile))) {
      CASCompleteSerializer serializer = (CASCompleteSerializer) is.readObject();
      deserializeCASComplete(serializer, (CASImpl) aCas);
      // Initialize the JCas sub-system which is the most often used API in DKPro Core
      // components
      aCas.getJCas();
    } catch (ClassNotFoundException e) {
      throw new IOException(e);
    } catch (CASException e) {
      log.error("Caught exception {}", e);
      System.exit(1);
    }
  }
}

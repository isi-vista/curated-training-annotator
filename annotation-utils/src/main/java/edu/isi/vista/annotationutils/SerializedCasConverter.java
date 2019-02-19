package edu.isi.vista.annotationutils;

import static org.apache.uima.cas.impl.Serialization.deserializeCASComplete;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
  private static final Logger log = LoggerFactory.getLogger(SerializedCasConverter.class);

  public static void main(String[] args) {
    // arg[0]: path of the binary CAS files (file extension .ser)
    // arg[1]: path of the output file
    if (args.length < 2) {
      log.error("Expecting two arguments: path to the binary CAS file and path of the output file");
      System.exit(1);
    }

    File input = new File(args[0]);
    File output = new File(args[1]);


    Map<CTEventSpan, List<Argument>> eventArguments = new HashMap<>();

    try {
      CAS cas = readCasFromFile(input);
      JCas jcas = cas.getJCas();

      for (CTEventSpanType type : JCasUtil.select(jcas, CTEventSpanType.class)) {

        CTEventSpan event = type.getGovernor();
        String role = type.getRelation_type();
        CTEventSpan argument = type.getDependent();

        if (!eventArguments.containsKey(event)) {
          eventArguments.put(event, new ArrayList<>());
        }
        eventArguments.get(event).add(new Argument(role, argument.getBegin(), argument.getEnd()));
      }

      List<CTEvent> events = new ArrayList<>();
      for (Map.Entry<CTEventSpan, List<Argument>> entry : eventArguments.entrySet()) {
        // create and add event
        CTEventSpan eventAnnotation = entry.getKey();
        events.add(
            new CTEvent(eventAnnotation.getBegin(), eventAnnotation.getEnd(), entry.getValue()));

      }

      ObjectMapper mapper = new ObjectMapper();
      try (OutputStream os = new FileOutputStream(output)) {
        mapper.writeValue(os, events);
        log.info("Exported to " + output.getAbsolutePath());
      }

    } catch (IOException | UIMAException e) {
      log.error("Caught exception {}", e);
      System.exit(1);
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

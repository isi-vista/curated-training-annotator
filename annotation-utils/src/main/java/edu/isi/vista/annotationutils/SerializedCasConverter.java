package edu.isi.vista.annotationutils;

import static org.apache.uima.cas.impl.Serialization.deserializeCASComplete;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReader;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReaderDescription;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.StringWriter;

import static org.apache.uima.fit.pipeline.SimplePipeline.runPipeline;
import de.tudarmstadt.ukp.dkpro.core.api.io.ResourceCollectionReaderBase;
import de.tudarmstadt.ukp.dkpro.core.io.bincas.BinaryCasReader;
import de.tudarmstadt.ukp.dkpro.core.io.xmi.XmiWriter;
import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.SerialFormat;
import org.apache.uima.cas.impl.CASCompleteSerializer;
import org.apache.uima.cas.impl.CASImpl;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.fit.factory.ConfigurationParameterFactory;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.json.JsonCasSerializer;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.CasCreationUtils;
import org.apache.uima.util.CasIOUtils;

public class SerializedCasConverter
{
    public static void main(String[] args) throws ResourceInitializationException
    {
        // one input expected: directory to find the binary CAS files (file extension .ser)

        File f = new File(args[0]);
        try {
            CAS cas = readCasFromFile(f);
            File outDir = new File("output");
            if (!outDir.exists()) {
                outDir.mkdir();
            }


            // Method 1, CasIOUtils to XML format
            try (OutputStream os = new FileOutputStream(new File(outDir,"xmi.txt"))) {
                CasIOUtils.save(cas, os, SerialFormat.XMI);
            }

            // Method 2, JsonCasSerializer to Json format
            try (OutputStream os = new FileOutputStream(new File(outDir,"json.txt"))) {
                JsonCasSerializer.jsonSerialize(cas, os);
            }

            // Method 3, DKPro
            runDkProConversion(cas, f, outDir);

        }
        catch (IOException | UIMAException e) {
            e.printStackTrace();
        }
    }

    private static CAS readCasFromFile(File serializedCasFile) throws IOException, UIMAException
    {
        if (!serializedCasFile.exists()) {
            throw new FileNotFoundException(serializedCasFile + " not found");
        }

        CAS cas = CasCreationUtils.createCas((TypeSystemDescription) null, null, null);
        readSerializedCas(cas, serializedCasFile);

        return cas;

    }

    private static void readSerializedCas(CAS aCas, File aFile)
            throws IOException
    {
        try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(aFile))) {
            CASCompleteSerializer serializer = (CASCompleteSerializer) is.readObject();
            deserializeCASComplete(serializer, (CASImpl) aCas);
            // Initialize the JCas sub-system which is the most often used API in DKPro Core
            // components
            aCas.getJCas();
        }
        catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        catch (CASException e) {
            e.printStackTrace();
        }
    }

    private static void runDkProConversion(CAS cas, File binaryFile, File outDir)
            throws UIMAException, IOException
    {
        runPipeline(
                createReaderDescription(BinaryCasReader.class,
                        BinaryCasReader.PARAM_SOURCE_LOCATION, binaryFile.getAbsolutePath(),
                        BinaryCasReader.PARAM_LANGUAGE, "en"),
                createEngineDescription(XmiWriter.class,
                        XmiWriter.PARAM_TARGET_LOCATION, outDir.getAbsolutePath(),
                        XmiWriter.PARAM_STRIP_EXTENSION, true));
    }

//    public JCas importCasFromFile(File aFile, Project aProject, String aFormatId, CollectionReaderDescription aReaderDescription)
//            throws UIMAException, IOException
//    {
//        // Prepare a CAS with the project type system
//        TypeSystemDescription allTypes = annotationService.getFullProjectTypeSystem(aProject);
//        CAS cas = JCasFactory.createJCas(allTypes).getCas();
//
//        // Convert the source document to CAS
//        ConfigurationParameterFactory.addConfigurationParameters(aReaderDescription,
//                ResourceCollectionReaderBase.PARAM_SOURCE_LOCATION,
//                aFile.getParentFile().getAbsolutePath(),
//                ResourceCollectionReaderBase.PARAM_PATTERNS,
//                new String[] { "[+]" + aFile.getName() });
//        CollectionReader reader = createReader(aReaderDescription);
//
//        if (!reader.hasNext()) {
//            throw new FileNotFoundException(
//                    "Source file [" + aFile.getName() + "] not found in [" + aFile.getPath() + "]");
//        }
//        reader.getNext(cas);
//        JCas jCas = cas.getJCas();
//
//        // Create sentence / token annotations if they are missing
//        boolean hasTokens = JCasUtil.exists(jCas, Token.class);
//        boolean hasSentences = JCasUtil.exists(jCas, Sentence.class);
//
//        //        if (!hasTokens || !hasSentences) {
//        //            AnalysisEngine pipeline = createEngine(createEngineDescription(
//        //                    BreakIteratorSegmenter.class,
//        //                    BreakIteratorSegmenter.PARAM_WRITE_TOKEN, !hasTokens,
//        //                    BreakIteratorSegmenter.PARAM_WRITE_SENTENCE, !hasSentences));
//        //            pipeline.process(jCas);
//        //        }
//
//        if (!hasSentences) {
//            splitSentences(jCas);
//        }
//
//        if (!hasTokens) {
//            tokenize(jCas);
//        }
//
//        if (!JCasUtil.exists(jCas, Token.class) || !JCasUtil.exists(jCas, Sentence.class)) {
//            throw new IOException("The document appears to be empty. Unable to detect any "
//                    + "tokens or sentences. Empty documents cannot be imported.");
//        }
//
//        return jCas;
//    }
}

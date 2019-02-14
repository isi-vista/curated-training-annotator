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
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import webanno.custom.*;

public class SerializedCasConverter
{
    private static final Logger log = LoggerFactory.getLogger(SerializedCasConverter.class);

    public static void main(String[] args)
    {
        // arg[0]: path of the binary CAS files (file extension .ser)
        // arg[1]: path of the output file
        if (args.length < 2) {
            log.error("Expecting two arguments: path to the binary CAS file and path of the output file");
            System.exit(1);
        }

        File input = new File(args[0]);
        File output = new File(args[1]);

        try {
            CAS cas = readCasFromFile(input);
            JCas jcas = cas.getJCas();

            List<Attack> attacks = new ArrayList<>();
            List<Demonstrate> demonstrates = new ArrayList<>();

            for (webanno.custom.Attack attack : JCasUtil.select(jcas, webanno.custom.Attack.class)) {
                List<Argument> arguments = new ArrayList<>();

                // get arguments for attack
                if (attack.getAttacker().size() > 0) {
                    Attacker attacker = attack.getAttacker(0).getTarget();
                    arguments.add(new Argument("Attacker", attacker.getBegin(), attacker.getEnd()));
                }
                if (attack.getInstrument().size() > 0) {
                    Instrument instrument = attack.getInstrument(0).getTarget();
                    arguments.add(new Argument("Instrument", instrument.getBegin(), instrument.getEnd()));
                }
                if (attack.getPlace().size() > 0) {
                    Place place = attack.getPlace(0).getTarget();
                    arguments.add(new Argument("Place", place.getBegin(), place.getEnd()));
                }
                if (attack.getTarget().size() > 0) {
                    Target target = attack.getTarget(0).getTarget();
                    arguments.add(new Argument("Target", target.getBegin(), target.getEnd()));
                }
                if (attack.getTime().size() > 0) {
                    Time time = attack.getTime(0).getTarget();
                    arguments.add(new Argument("Time", time.getBegin(), time.getEnd()));
                }

                attacks.add(new Attack(attack.getBegin(), attack.getEnd(), arguments));
            }


            for (webanno.custom.Demonstrate demonstrate : JCasUtil.select(jcas, webanno.custom.Demonstrate.class)) {
                List<Argument> arguments = new ArrayList<>();

                // get arguments for demonstrate
                if (demonstrate.getEntity().size() > 0) {
                    Entity entity = demonstrate.getEntity(0).getTarget();
                    arguments.add(new Argument("Entity", entity.getBegin(), entity.getEnd()));
                }
                if (demonstrate.getPlace().size() > 0) {
                    Place place = demonstrate.getPlace(0).getTarget();
                    arguments.add(new Argument("Place", place.getBegin(), place.getEnd()));
                }
                if (demonstrate.getTime().size() > 0) {
                    Time time = demonstrate.getTime(0).getTarget();
                    arguments.add(new Argument("Time", time.getBegin(), time.getEnd()));
                }

                demonstrates.add(new Demonstrate(demonstrate.getBegin(), demonstrate.getEnd(), arguments));
            }

            Conflict conflict = new Conflict(attacks, demonstrates);
            ObjectMapper mapper = new ObjectMapper();
            try (OutputStream os = new FileOutputStream(output)) {
                mapper.writeValue(os, conflict);
            }
        }
        catch (IOException | UIMAException e) {
            log.error("Caught exception {}", e);
            System.exit(1);
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
            log.error("Caught exception {}", e);
            System.exit(1);
        }
    }
}

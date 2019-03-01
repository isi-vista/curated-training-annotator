package edu.isi.vista.projectcreator;

import edu.isi.nlp.parameters.Parameters;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.core.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InceptionProjectCreator {

  private static final String USAGE =
      "InceptionProjectCreator param_file\n"
          + "\tparam file consists of :-separated key-value pairs\n"
          + "\tThe required parameters are:\n"
          + "\tmethod: IMPORT or CREATE\n"
          + "\tfile: the path of the exported project (zip)";

  private static final String PARAM_METHOD = "method";

  private static final String PARAM_FILE = "file";

  private static final String PARAM_USERNAME = "username";

  private static final String PARAM_PASSWORD = "password";

  private static final String BASE_URL = "http://localhost:8080/inception-app-webapp/api/aero/v1";

  private static final String IMPORT = "import";

  private static final String PROJECTS = "projects";

  private static final Logger log = LoggerFactory.getLogger(InceptionProjectCreator.class);

  public static void main(String[] args) throws IOException {

    Parameters parameters = null;

    // get parameters from parameter file
    if (args.length == 1) {
      parameters = Parameters.loadSerifStyle(new File(args[0]));
    } else {
      System.err.println(USAGE);
      System.exit(1);
    }

    String method = parameters.getString(PARAM_METHOD);
    File file = parameters.getExistingFile(PARAM_FILE);
    String username = parameters.getString(PARAM_USERNAME);
    String password = parameters.getString(PARAM_PASSWORD);

    try {
      if (method.toLowerCase().equals("import")) {
        ResponseStatus status = importProject(file, username, password);
        if (status.getCode() != 200) {
          log.error("Import failed: " + status.getMessage());
        }
      }

    } catch (IOException e) {
      log.error("Exception occurred ", e);
    } catch (AuthenticationException e) {
      e.printStackTrace();
    }
  }

  public static String getProjects(String username, String password)
      throws IOException, AuthenticationException {

    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpGet httpGet = new HttpGet(BASE_URL + "/" + PROJECTS);

      UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);
      httpGet.addHeader(new BasicScheme().authenticate(creds, httpGet, null));

      CloseableHttpResponse response = client.execute(httpGet);

      InputStream responseStream = response.getEntity().getContent();
      InputStreamReader reader = new InputStreamReader(responseStream);
      StringWriter writer = new StringWriter();
      IOUtils.copy(reader, writer);
      client.close();
      return writer.toString();
    }
  }

  public static ResponseStatus importProject(File zipFile, String username, String password)
      throws IOException, AuthenticationException {

    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpPost httpPost = new HttpPost(BASE_URL + "/" + PROJECTS + "/" + IMPORT);

      UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);
      httpPost.addHeader(new BasicScheme().authenticate(creds, httpPost, null));

      MultipartEntityBuilder builder = MultipartEntityBuilder.create();
      builder.addBinaryBody(
          "file", zipFile, ContentType.APPLICATION_OCTET_STREAM, zipFile.getName());

      HttpEntity entity = builder.build();
      httpPost.setEntity(entity);

      CloseableHttpResponse response = client.execute(httpPost);
      InputStream responseStream = response.getEntity().getContent();
      InputStreamReader reader = new InputStreamReader(responseStream);
      StringWriter writer = new StringWriter();
      IOUtils.copy(reader, writer);
      log.info(writer.toString());

      return new ResponseStatus(
          response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
    }
  }
}

package edu.isi.vista.gigawordIndexer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;

import org.apache.commons.io.FilenameUtils;
import org.apache.uima.UIMAException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportResource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ProjectService;
import de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst;
import de.tudarmstadt.ukp.clarin.webanno.automation.service.AutomationService;
import de.tudarmstadt.ukp.clarin.webanno.automation.service.export.AutomationMiraTemplateExporter;
import de.tudarmstadt.ukp.clarin.webanno.automation.service.export.AutomationTrainingDocumentExporter;
import de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ProjectPermission;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.support.SettingsUtil;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.AnnotationPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.curation.page.CurationPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.monitoring.page.AgreementPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.monitoring.page.MonitoringPageMenuItem;
import edu.isi.vista.gigawordIndexer.GigawordFileProcessor.Article;

@SpringBootApplication
@ComponentScan(
    basePackages = {"de.tudarmstadt.ukp.inception.app", "de.tudarmstadt.ukp.clarin.webanno"},
    excludeFilters = {
      @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
      @Filter(
          type = FilterType.ASSIGNABLE_TYPE,
          classes = {
            // The INCEpTION dashboard uses a per-project view while WebAnno uses a global
            // activation strategies for menu items. Thus, we need to re-implement the menu
            // items for INCEpTION.
            AnnotationPageMenuItem.class,
            CurationPageMenuItem.class,
            MonitoringPageMenuItem.class,
            AgreementPageMenuItem.class,
            // INCEpTION uses its recommenders, not the WebAnno automation code
            AutomationService.class,
            AutomationMiraTemplateExporter.class,
            AutomationTrainingDocumentExporter.class
          })
    })
@EntityScan(
    basePackages = {
      // Include WebAnno entity packages separately so we can skip the automation entities!
      "de.tudarmstadt.ukp.clarin.webanno.model",
      "de.tudarmstadt.ukp.clarin.webanno.security"
    })
@ImportResource({"classpath:application-context.xml"})
public class GigawordIndexer implements CommandLineRunner {

  @Autowired
  @Qualifier("authenticationManager1")
  private AuthenticationManager authManager;

  //  private @Autowired UserDao userRepository;

  private @Autowired ProjectService projectService;

  private @Autowired DocumentService documentService;

  private @Autowired AnnotationSchemaService annotationSchemaService;

  private static final String PROJECT_NAME_GW = "Gigawords";

  public GigawordIndexer() {}

  private final Logger log = LoggerFactory.getLogger(getClass());

  public void run(String... args) {

    if (args.length == 0) {
      log.error("The file path to a Gigaword GZip file required");
      System.exit(-1);
    }

    String gzPath = args[0];
    log.info("GigawordIndexer is running...");
    log.info("Parsing Gigaword from file: {}", gzPath);

    // Authenticate user (admin)
    log.info("Authenticating user admin... ");
    UsernamePasswordAuthenticationToken authReq =
        new UsernamePasswordAuthenticationToken("admin", "admin");
    Authentication auth = authManager.authenticate(authReq);
    SecurityContextHolder.getContext().setAuthentication(auth);

    // create a project and upload documents
    try {

      // delete project if already exists
      if (projectService.existsProject(PROJECT_NAME_GW)) {
        Project projectGigawords = projectService.getProject(PROJECT_NAME_GW);
        projectService.removeProject(projectGigawords);
      }

      // create a project
      log.info("Creating project {}", PROJECT_NAME_GW);
      Project project = new Project();
      project.setName(PROJECT_NAME_GW);
      project.setMode(WebAnnoConst.PROJECT_TYPE_ANNOTATION);
      projectService.createProject(project);
      projectService.createProjectPermission(
          new ProjectPermission(project, "admin", PermissionLevel.MANAGER));
      projectService.createProjectPermission(
          new ProjectPermission(project, "admin", PermissionLevel.CURATOR));
      projectService.createProjectPermission(
          new ProjectPermission(project, "admin", PermissionLevel.ANNOTATOR));
      annotationSchemaService.initializeProject(project);

      // check project created
      if (projectService.existsProject(PROJECT_NAME_GW)) {
        Project projectGigawords = projectService.getProject(PROJECT_NAME_GW);
        log.info(
            "Project {} created on {}", projectGigawords.getName(), projectGigawords.getCreated());

        // parse gigaword articles
        File file = new File(gzPath);
        String contentType = Files.probeContentType(file.toPath());
        if (contentType != null && contentType.equalsIgnoreCase("application/gzip")
            || (FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("gz"))) {

          // get articles from GZip file
          GigawordFileProcessor proc = new GigawordFileProcessor(file);
          Iterator<Article> articles = proc.iterator();
          while (articles.hasNext()) {
            Article article = articles.next();
            SourceDocument sourceDocument = new SourceDocument();
            sourceDocument.setName(article.getId());
            sourceDocument.setProject(project);
            sourceDocument.setFormat("text");

            InputStream is = new ByteArrayInputStream(article.getText().getBytes());
            documentService.uploadSourceDocument(is, sourceDocument);
            log.info("Document uploaded: {}", article.getId());
          }
        } else {
          log.error("Not a valid GZip file: {}", gzPath);
          System.exit(-1);
        }

      } else {
        log.error("Project {} not created", PROJECT_NAME_GW);
        System.exit(-1);
      }

    } catch (IOException e) {
      log.error(
          "Exception occured. Creating project without permission or removing a project that does not exist.");
      e.printStackTrace();
      System.exit(-1);
    } catch (UIMAException e) {
      log.error("Conversion error when uploading a document");
      e.printStackTrace();
      System.exit(-1);
    }
  }

  public static void main(String[] args) {

    SpringApplicationBuilder builder = new SpringApplicationBuilder();
    builder.properties("running.from.commandline=true");
    builder.bannerMode(Banner.Mode.OFF);
    builder.initializers(new GigawordApplicationContextInitializer());

    // need this line to set user home directory to /.inception
    // otherwise it will be defaulted to ./webanno and documents will be uploaded here
    // and Inception app won't be able to find uploaded documents.
    SettingsUtil.customizeApplication("inception.home", ".inception");
    builder.properties(
        "spring.config.location="
            + "${inception.home:${user.home}/.inception}/settings.properties");
    builder.sources(GigawordIndexer.class);
    builder.run(args).close();
  }
}

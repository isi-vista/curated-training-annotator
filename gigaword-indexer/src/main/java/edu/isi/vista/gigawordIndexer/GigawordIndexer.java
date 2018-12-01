package edu.isi.vista.gigawordIndexer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.AnnotationPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.curation.page.CurationPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.monitoring.page.AgreementPageMenuItem;
import de.tudarmstadt.ukp.clarin.webanno.ui.monitoring.page.MonitoringPageMenuItem;

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

  private @Autowired UserDao userRepository;

  private @Autowired ProjectService projectService;

  private @Autowired DocumentService documentService;

  private @Autowired AnnotationSchemaService annotationSchemaService;

  private static final String PROJECT_NAME_GW = "Gigawords";

  public GigawordIndexer() {}

  private final Logger log = LoggerFactory.getLogger(getClass());

  public void run(String... args) {

    log.info("GigawordIndexer is running...");

    // TODO
    // parse gigaword
    // create Project
    // create SourceDocuments
    // import document text

    // Check if authenticated
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    log.info("Authentication: " + a);

    // Authenticate user (admin)
    log.info("Authenticating user admin... ");
    UsernamePasswordAuthenticationToken authReq =
        new UsernamePasswordAuthenticationToken("admin", "admin");
    Authentication auth = authManager.authenticate(authReq);
    SecurityContextHolder.getContext().setAuthentication(auth);

    // Test current user is admin
    User user = userRepository.getCurrentUser();
    log.info("Current user: " + user.getUsername());

    // List projects
    log.info("List Projects: ");
    List<Project> projects = projectService.listAccessibleProjects(user);
    for (Project project : projects) {
      log.info("	" + project.getName());
    }

    // create and delete a project
    try {

      // delete test project if already exists
      if (projectService.existsProject(PROJECT_NAME_GW)) {
        Project projectGigawords = projectService.getProject(PROJECT_NAME_GW);
        projectService.removeProject(projectGigawords);
      }

      // create a project
      log.info("Creating project " + PROJECT_NAME_GW);
      Project project = new Project();
      project.setName(PROJECT_NAME_GW);
      project.setMode(WebAnnoConst.PROJECT_TYPE_ANNOTATION);
      projectService.createProject(project);
      annotationSchemaService.initializeProject(project);

      // create a document
      SourceDocument sourceDocument = new SourceDocument();
      sourceDocument.setName("Gigaword Doc 1");
      sourceDocument.setProject(project);
      sourceDocument.setFormat("text");

      String fileContent = "The capital of Galicia is Santiago de Compostela.";

      // upload document
      InputStream fileStream =
          new ByteArrayInputStream(fileContent.getBytes(StandardCharsets.UTF_8));

      documentService.uploadSourceDocument(fileStream, sourceDocument);

      // check project and document created
      if (projectService.existsProject(PROJECT_NAME_GW)) {
        Project projectGigawords = projectService.getProject(PROJECT_NAME_GW);
        log.info(
            "Project "
                + projectGigawords.getName()
                + " created on "
                + projectGigawords.getCreated());

        log.info("List documents under project " + projectGigawords);
        List<SourceDocument> documents = documentService.listSourceDocuments(projectGigawords);
        for (SourceDocument doc : documents) {
          log.info("  " + doc.getName());
        }

        // delete project
        projectService.removeProject(projectGigawords);
      } else log.info("Project " + PROJECT_NAME_GW + " not created.");

    } catch (IOException e) {
      log.error(
          "Exception occured. Creating project without permission or removing a project that does not exist.");
      e.printStackTrace();
    } catch (UIMAException e) {
      log.error("Conversion error when uploading a document");
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {

    SpringApplicationBuilder builder = new SpringApplicationBuilder();
    builder.properties("running.from.commandline=true");
    builder.bannerMode(Banner.Mode.OFF);
    builder.initializers(new GigawordApplicationContextInitializer());
    builder.properties(
        "spring.config.location="
            + "${inception.home:${user.home}/.inception}/settings.properties");
    builder.sources(GigawordIndexer.class);
    builder.run(args);
  }
}
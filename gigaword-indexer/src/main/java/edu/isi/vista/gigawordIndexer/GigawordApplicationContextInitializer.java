package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;

import de.tudarmstadt.ukp.clarin.webanno.support.SettingsUtil;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.LoggingFilter;

/**
 * @author jenniferchen
 *     <p>Initialize the application context. Load default settings defined by Webanno and activate
 *     bean profile (preAuthSecurity-context.xml or security-context.xml) depending on
 *     authentication mode (preauth or database).
 */
public class GigawordApplicationContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  public static final String PROFILE_PREAUTH = "auto-mode-preauth";

  public static final String PROFILE_DATABASE = "auto-mode-builtin";

  private static final String AUTH_MODE_PREAUTH = "preauth";

  private final Logger log = LoggerFactory.getLogger(getClass());

  @Override
  public void initialize(ConfigurableApplicationContext aApplicationContext) {

    LoggingFilter.setLoggingUsername("Gigawords");

    ConfigurableEnvironment aEnvironment = aApplicationContext.getEnvironment();

    File settings = SettingsUtil.getSettingsFile();

    // If settings were found, add them to the environment
    if (settings != null) {
      log.info("Settings: " + settings);
      try {
        aEnvironment
            .getPropertySources()
            .addFirst(new ResourcePropertySource(new FileSystemResource(settings)));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    // Activate bean profile depending on authentication mode
    // this is to specify which AuthenticationManager is being used.
    if (AUTH_MODE_PREAUTH.equals(aEnvironment.getProperty(SettingsUtil.CFG_AUTH_MODE))) {
      aEnvironment.setActiveProfiles(PROFILE_PREAUTH);
      log.info("Authentication: pre-auth");
    } else {
      aEnvironment.setActiveProfiles(PROFILE_DATABASE);
      log.info("Authentication: database");
    }
  }
}

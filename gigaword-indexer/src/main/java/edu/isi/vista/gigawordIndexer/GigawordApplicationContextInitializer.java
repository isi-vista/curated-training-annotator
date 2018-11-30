package edu.isi.vista.gigawordIndexer;

import java.io.File;
import java.io.IOException;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;

import de.tudarmstadt.ukp.clarin.webanno.support.SettingsUtil;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.LoggingFilter;

public class GigawordApplicationContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  public static final String PROFILE_PREAUTH = "auto-mode-preauth";
  public static final String PROFILE_DATABASE = "auto-mode-builtin";

  private static final String AUTH_MODE_PREAUTH = "preauth";

  @Override
  public void initialize(ConfigurableApplicationContext aApplicationContext) {
    LoggingFilter.setLoggingUsername("SYSTEM");

    ConfigurableEnvironment aEnvironment = aApplicationContext.getEnvironment();

    File settings = SettingsUtil.getSettingsFile();

    // If settings were found, add them to the environment
    if (settings != null) {
      System.out.println("Settings: " + settings);
      try {
        aEnvironment
            .getPropertySources()
            .addFirst(new ResourcePropertySource(new FileSystemResource(settings)));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    // Activate bean profile depending on authentication mode
    if (AUTH_MODE_PREAUTH.equals(aEnvironment.getProperty(SettingsUtil.CFG_AUTH_MODE))) {
      aEnvironment.setActiveProfiles(PROFILE_PREAUTH);
      System.out.println("Authentication: pre-auth");
    } else {
      aEnvironment.setActiveProfiles(PROFILE_DATABASE);
      System.out.println("Authentication: database");
    }
  }
}

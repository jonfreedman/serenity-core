package net.thucydides.core.webdriver.appium;

import net.serenitybdd.core.environment.EnvironmentSpecificConfiguration;
import net.serenitybdd.core.webdriver.RemoteDriver;
import net.serenitybdd.core.webdriver.driverproviders.AddLoggingPreferences;
import net.serenitybdd.core.webdriver.driverproviders.SetProxyConfiguration;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.PathProcessor;
import net.thucydides.core.webdriver.*;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AppiumConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppiumConfiguration.class);
    private static final String DEFAULT_URL = "http://127.0.0.1:4723/wd/hub";
    private final EnvironmentVariables environmentVariables;

    private AppiumConfiguration(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public static AppiumConfiguration from(EnvironmentVariables environmentVariables) {
        return new AppiumConfiguration(environmentVariables);
    }

    /**
     * Define the platform based on the {@link DesiredCapabilities} of the {@link WebDriver} first. If that doesn't
     * work, fall back to the Appium platform defined in the system properties or the context.
     * Must be either ios or android.
     */
    public MobilePlatform getTargetPlatform(WebDriver driver) {
        String PLATFORM_NAME = "platformName";
        try {
            Capabilities caps = RemoteDriver.of(driver).getCapabilities();
            if (caps.getCapabilityNames().contains(PLATFORM_NAME)) {
                return MobilePlatform.valueOf(
                        caps.getCapability(PLATFORM_NAME).toString().toUpperCase());
            }
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Platform was not a MobilePlatform. Falling back to other platform definitions.");
        } catch (ClassCastException e) {
            LOGGER.debug("The driver could not be cast to RemoteWebDriver. Falling back to other platform definitions.");
        }

        return getTargetPlatform();
    }

    /**
     * Return the Appium platform defined in the system properties or the context. Must be either ios or android.
     */
    public MobilePlatform getTargetPlatform() {
        Optional contextPlatform = Stream.of(definedContext())
                                         .filter(platform -> platform.isDefined)
                                         .findFirst();
        if (contextPlatform.isPresent()) {
            return (MobilePlatform) contextPlatform.get();
        }

        return Stream.of(definedTargetPlatform())
                .filter(platform -> platform.isDefined)
                .findFirst()
                .orElseThrow(() -> new ThucydidesConfigurationException("The appium.platformName needs to be specified (either IOS or ANDROID)"));
    }

    /**
     * Return the Appium platform defined in the system properties, or NONE if no platform is defined.
     */
    public MobilePlatform definedTargetPlatform() {
        String targetPlatform = ThucydidesSystemProperty.APPIUM_PLATFORMNAME.from(environmentVariables,"NONE");
        try {
            return MobilePlatform.valueOf(targetPlatform.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ThucydidesConfigurationException("Illegal appium.platformName value (needs to be either IOS or ANDROID):" + targetPlatform);
        }
    }

    public MobilePlatform definedContext() {
        String targetPlatform = ThucydidesSystemProperty.CONTEXT.from(environmentVariables, "NONE");
        try {
            return MobilePlatform.valueOf(targetPlatform.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.debug("The provided context ({}) could not be used as the MobilePlatform", targetPlatform);
        }
        return MobilePlatform.NONE;
    }

    public URL getUrl() {
        String url = ThucydidesSystemProperty.APPIUM_HUB.from(environmentVariables, DEFAULT_URL);
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new ThucydidesConfigurationException("The appium.hub URL needs to be specified");
        }
    }

    public DesiredCapabilities getCapabilities() {
        return getCapabilities("");
    }

    public DesiredCapabilities getCapabilities(String options) {
        DesiredCapabilities capabilities = new DesiredCapabilities();

        SetProxyConfiguration.from(environmentVariables).in(capabilities);
        AddLoggingPreferences.from(environmentVariables).to(capabilities);

        Properties appiumProperties = getProperties(options);
        for (Object key : appiumProperties.keySet()) {
            capabilities.setCapability(key.toString(), appiumProperties.getProperty(key.toString()));
            capabilities.asMap();
        }
        return capabilities;
    }

    public Properties getProperties(String options) {
        return appiumPropertiesFrom(environmentVariables, options);
    }

    private Properties appiumPropertiesFrom(EnvironmentVariables environmentVariables, String options) {

        Properties appiumProperties = new Properties();
List<String> appiumKeys =
                environmentVariables.getKeys()
                        .stream()
                        .map(key->key.replaceFirst("environment.*.appium","appium"))
                        .distinct()
                        .filter(key -> key.startsWith("appium."))
                        .collect(Collectors.toList());

        for (String key : appiumKeys) {

            String specifiedAppPath = EnvironmentSpecificConfiguration.from(environmentVariables)
                    .getOptionalProperty(key)
                    .orElse("");

            String value = isAppProperty(key) ? appPathFrom(specifiedAppPath) : specifiedAppPath;
            String simplifiedKey = key.replace("appium.", "");
            appiumProperties.setProperty(simplifiedKey, value.trim());
        }

        Map<String, String> optionsMap = OptionsMap.from(options);
        for(String key : optionsMap.keySet()) {
            appiumProperties.setProperty(key, optionsMap.get(key));
        }
        ensureAppOrBrowserPathDefinedIn(appiumProperties);
        return appiumProperties;
    }

    private void ensureAppOrBrowserPathDefinedIn(Properties appiumProperties) {
        if (!appiumProperties.containsKey("app") && !appiumProperties.containsKey("browserName")) {
            throw new ThucydidesConfigurationException("The browser under test or path to the app needs to be provided in the appium.app or appium.browserName property.");
        }
    }

    private String appPathFrom(String propertyValue) {
        return new PathProcessor().normalize(propertyValue);
    }

    private boolean isAppProperty(String key) {
        return key.equals("appium.app");
    }

    public boolean isDefined() {
        return getTargetPlatform() != MobilePlatform.NONE;
    }
}

package net.bittx.dlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BootMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(BootMain.class);
    private static final String PID_FILE_NAME = "pid";
    private static ConfigurableApplicationContext ac;

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BootMain.class);
        //app.addListeners(new ApplicationPidFileWriter(PID_FILE_NAME));
        //app.addListeners(new ApplicationPidFileWriter());
        app.setRegisterShutdownHook(true);
        ac = app.run(args);

        for (String str : ac.getEnvironment().getActiveProfiles()) {
            LOGGER.info(str);
        }
        LOGGER.info("Boot Server started.");
    }
}
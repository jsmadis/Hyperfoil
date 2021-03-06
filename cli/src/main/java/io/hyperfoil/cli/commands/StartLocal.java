package io.hyperfoil.cli.commands;

import java.util.ServiceLoader;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.io.FileResource;
import org.aesh.io.Resource;
import org.aesh.terminal.utils.ANSI;

import io.hyperfoil.cli.context.HyperfoilCommandInvocation;
import io.hyperfoil.internal.Controller;
import io.hyperfoil.internal.Properties;

@CommandDefinition(name = "start-local", description = "Start non-clustered controller within the CLI process.")
public class StartLocal extends ServerCommand {
   @Option(shortName = 'l', description = "Default log level for controller log.", defaultValue = "")
   private String logLevel;

   @Option(shortName = 'q', description = "Do not print anything on output in this command.", hasValue = false)
   private boolean quiet;

   @Argument(description = "Root directory used for the controller.")
   private Resource rootDir;

   @Override
   public CommandResult execute(HyperfoilCommandInvocation invocation) throws CommandException {
      if (invocation.context().localControllerHost() != null || invocation.context().localControllerPort() > 0) {
         if (!quiet) {
            invocation.println(ANSI.YELLOW_TEXT + "WARNING: local controller is already running, not starting." + ANSI.RESET);
         }
      } else {
         Controller.Factory factory = null;
         for (Controller.Factory f : ServiceLoader.load(Controller.Factory.class)) {
            factory = f;
            break;
         }
         if (factory == null) {
            throw new CommandException("Controller is not on the classpath, cannot start.");
         }
         if (rootDir != null && rootDir.exists() && !(rootDir.isDirectory() && rootDir instanceof FileResource)) {
            if (!quiet) {
               invocation.println("You are trying to start Hyperfoil controller with root dir " + rootDir);
            }
            throw new CommandException(rootDir + " exists but it is not a directory");
         }
         if (!quiet) {
            invocation.println("Starting controller in " + (rootDir == null ? "default directory (/tmp/hyperfoil)" : rootDir.getAbsolutePath()));
         }
         // disable logs from controller
         System.setProperty(Properties.LOG4J2_CONFIGURATION_FILE, getClass().getClassLoader().getResource("log4j2-local-controller.xml").toString());
         if (!logLevel.isEmpty()) {
            System.setProperty(Properties.CONTROLLER_LOG_LEVEL, logLevel);
         }
         Controller controller = factory.start(rootDir == null ? null : ((FileResource) rootDir).getFile().toPath());
         invocation.context().setLocalControllerHost(controller.host());
         invocation.context().setLocalControllerPort(controller.port());
         if (!quiet) {
            invocation.println("Controller started, listening on " + controller.host() + ":" + controller.port());
         }
         invocation.context().addCleanup(() -> controller.stop());
      }
      if (!quiet) {
         invocation.println("Connecting to the controller...");
      }
      if (invocation.context().client() != null) {
         invocation.context().client().close();
      }
      connect(invocation, invocation.context().localControllerHost(), invocation.context().localControllerPort(), quiet);
      return CommandResult.SUCCESS;
   }
}

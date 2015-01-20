package com.supermomonga.rukkit;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.Configuration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.transform;

public class JRubyPlugin extends JavaPlugin {
  /** A {@link Listener} implementation class object */
  private static final Class<Listener> eventHandlerClass;

  /** Recognized all Rukkit's events */
  private static final ImmutableSet<RukkitEvent> allEvents;

  static {
    try {
      // to suppress memory amount
      ClassPool.doPruning = true;

      ClassPool cp = ClassPool.getDefault();
      cp.insertClassPath(new ClassClassPath(JRubyPlugin.class));
      CtClass clazz = cp.makeClass(JRubyPlugin.class.getName() + "$DynamicEventHandler");

      // setup class template
      clazz.setModifiers(Modifier.PRIVATE);
      clazz.addInterface(cp.get(Listener.class.getName()));
      {
        CtField field = new CtField(cp.get(JRubyPlugin.class.getName()), "that", clazz);

        field.setModifiers(Modifier.PRIVATE | Modifier.FINAL);

        clazz.addField(field);
      }
      {
        CtConstructor ctor = new CtConstructor(new CtClass[]{cp.get(JRubyPlugin.class.getName())}, clazz);

        ctor.setBody("this.that = $1;");

        clazz.addConstructor(ctor);
      }

      allEvents = ImmutableSet.copyOf(listEvents());
      for(RukkitEvent event : allEvents) {
        CtMethod method = new CtMethod(CtClass.voidType, "on" + event.getJavaEventName(), new CtClass[0], clazz);

        // set annotation
        {
          ConstPool constPool = clazz.getClassFile().getConstPool();
          AnnotationsAttribute attr = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);

          attr.addAnnotation(new Annotation(EventHandler.class.getName(), constPool));

          method.getMethodInfo().addAttribute(attr);
        }
        method.setModifiers(Modifier.PUBLIC | Modifier.FINAL);
        method.addParameter(cp.get(event.getEventClass().getName()));
        // XXX: work around, failed to resolve overloading if body is "fireEvent(\"on_%s\", $1);"
        method.setBody(String.format("that.fireEvent(\"on_%s\", new Object[]{$1});", event.getRubyEventName()));

        clazz.addMethod(method);
      }

      // construct class object in memory
      @SuppressWarnings("unchecked")
      Class<Listener> madeClass = clazz.toClass(JRubyPlugin.class.getClassLoader(), JRubyPlugin.class.getProtectionDomain());
      eventHandlerClass = madeClass;
    }
    catch(Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Returns all Rukkit's events.
   *
   * @return The Rukkit's events
   */
  private static Iterable<RukkitEvent> listEvents() {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if(compiler == null) {
      throw new IllegalStateException();
    }

    JavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    try {
      Set<RukkitEvent> events = new LinkedHashSet<>();

      Pattern pattern = Pattern.compile("\\((?<relpath>org/bukkit/event/(?:[^/]+/)*[^/]+?Event\\.class)\\)");
      Set<JavaFileObject.Kind> kinds = new HashSet<JavaFileObject.Kind>();
      {
        kinds.add(JavaFileObject.Kind.CLASS);
      }
      for(JavaFileObject file : fileManager.list(StandardLocation.CLASS_PATH, "org.bukkit.event", kinds, true)) {
        Matcher matcher = pattern.matcher(file.getName());

        if(matcher.find()) {
          String relpath = matcher.group("relpath");
          String canonName = relpath.replaceFirst("\\.class$", "").replace('/', '.');
          try {
            Class<?> eventClass = Class.forName(canonName);

            if(eventClass.getAnnotation(Deprecated.class) == null) {
              if(!eventClass.isInterface() && (eventClass.getModifiers() & java.lang.reflect.Modifier.ABSTRACT) != java.lang.reflect.Modifier.ABSTRACT) {
                events.add(new RukkitEvent(eventClass));
              }
            }
          }
          catch(ClassNotFoundException e) {
            throw new AssertionError(String.format("Cannot convert `%s' extracted from `%s' to correct class name.", relpath, file.getName()), e);
          }
        }
      }

      return events;
    }
    catch(IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates an implementation for {@link Listener}.
   *
   * @return The implementation for {@link Listener}
   * @throws RuntimeException When there are any exceptions or errors
   */
  private static Listener newDynamicEventHandler(JRubyPlugin that) {
    try {
      Constructor<?> ctor = eventHandlerClass.getConstructor(JRubyPlugin.class);
      return (Listener)ctor.newInstance(that);
    }
    catch(NoSuchMethodException | IllegalAccessException | InstantiationException e) {
      throw new AssertionError(e);
    }
    catch(InvocationTargetException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  private final AtomicReference<RubyEnvironment> jruby = new AtomicReference<>();
  private HashMap<String, Object> eventHandlers = new HashMap<String, Object>();

  public void initializeRuby() {
    getLogger().info("--> Initialize a ruby environment.");

    final ExecutorService service= Executors.newCachedThreadPool();
    final Runnable initializer = () -> {
      try {
        final RubyEnvironment newEnv = new RubyEnvironment(this);

        newEnv.initialize();

        getLogger().info("--> Load rukkit core scripts.");
        newEnv.loadCoreScripts();

        newEnv.callMethod(
          newEnv.getCoreModule(),
          "clone_or_update_repository",
          newEnv.getRepositoryDir().toString(),
          getConfig().getString("rukkit.repository")
        );

        final Server server = Bukkit.getServer();
        synchronized(server) {
          server.resetRecipes();

          getLogger().info("--> Load rukkit user scripts.");
          newEnv.loadUserScripts();

          getLogger().info("--> Load rukkit user plugins.");
          newEnv.loadUserPlugins();

          // switch
          final RubyEnvironment oldEnv = jruby.get();
          if(oldEnv != null) {
            oldEnv.terminate();
          }
          if(jruby.compareAndSet(oldEnv, newEnv)) {
            getLogger().info("--> Updated.");
          }
          else {
            getLogger().warning("--> Other update task has done, skipped.");
          }
        }
      }
      finally {
        service.shutdownNow();
      }
    };

    // first time
    if(jruby.get() == null) {
      try {
        // sync
        getLogger().info("--> Start to update rukkit plugins.");
        service.submit(initializer).get();
      }
      catch(InterruptedException e)
      {
        getLogger().info("--> Canceled.");
      }
      catch(ExecutionException e)
      {
        getLogger().warning("--> Failed to update rukkit.");
        getLogger().warning(Throwables.getStackTraceAsString(e));
      }
    }
    else {
      // async
      getLogger().info("--> Schedule to update rukkit plugins.");
      service.execute(initializer);
    }
    service.shutdown();
  }

  // XXX: work around, javassist cannot handle enclosing private method
  void fireEvent(String method, Object...args) {
    final RubyEnvironment env = jruby.get();
    checkState(env != null);

    List<Object> rubyArgs = new ArrayList<>(1 + args.length);

    rubyArgs.add(method);
    for(Object arg : args) {
      rubyArgs.add(arg);
    }

    env.callMethod(env.getCoreModule(), "fire_event", rubyArgs.toArray());
  }

  private InputStream openResource(String resourceName) throws IOException {
    InputStream resource = this.getClass().getClassLoader().getResourceAsStream(resourceName);

    if(resource == null) {
      throw new IOException("No such resource `" + resourceName + "'.");
    }

    return resource;
  }

  private void applyEventHandler() {
    getServer().getPluginManager().registerEvents(newDynamicEventHandler(this), this);
  }

  @Override
  public void onEnable() {
    Configuration config = getConfig();

    initializeRuby();

    getLogger().info("--> Save all event names to file.");
    try {
      if(config.contains("rukkit.event_filename")) {
        writeEvents(new File(config.getString("rukkit.event_filename")).toPath(), allEvents);
        getLogger().info("--> Saved.");
      }
      else {
        getLogger().info("--> No filename, skipped.");
      }
    }
    catch(Exception e) {
      getLogger().warning("--> Failed to save event names to file:");
      getLogger().warning(Throwables.getStackTraceAsString(e));
    }

    getLogger().info("Rukkit enabled!");

    applyEventHandler();
  }

  @Override
  public void onDisable() {
    getLogger().info("Rukkit disabled!");
  }

  @Override
  public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
    fireEvent("on_command", sender, command, label, args);
    return true;
  }

  private void writeEvents(Path path, Iterable<? extends RukkitEvent> events) throws IOException {
    checkNotNull(path);
    checkNotNull(events);
    checkArgument(!path.toFile().exists() || path.toFile().canWrite());

    Iterable<String> lines = transform(events, new Function<RukkitEvent, String>(){
      @Override
      public String apply(RukkitEvent input) {
        return input.getRubyEventName();
      }
    });
    Files.write(path, lines, Charset.forName("UTF-8"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
  }
}

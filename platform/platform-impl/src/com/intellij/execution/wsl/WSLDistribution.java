// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.wsl;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialPromptDialog;
import com.intellij.execution.CommandLineUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.*;
import com.intellij.ide.IdeBundle;
import com.intellij.openapi.application.Experiments;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.impl.local.LocalFileSystemBase;
import com.intellij.util.Consumer;
import com.intellij.util.Functions;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;

import static com.intellij.execution.wsl.WSLUtil.LOG;

/**
 * Represents a single linux distribution in WSL, installed after <a href="https://blogs.msdn.microsoft.com/commandline/2017/10/11/whats-new-in-wsl-in-windows-10-fall-creators-update/">Fall Creators Update</a>
 *
 * @see WSLUtil
 * @see WSLDistributionWithRoot
 */
public class WSLDistribution {
  public static final String DEFAULT_WSL_MNT_ROOT = "/mnt/";
  private static final int RESOLVE_SYMLINK_TIMEOUT = 10000;
  private static final String RUN_PARAMETER = "run";
  public static final String UNC_PREFIX = "\\\\wsl$\\";
  private static final String WSLENV = "WSLENV";

  private static final Key<ProcessListener> SUDO_LISTENER_KEY = Key.create("WSL sudo listener");

  @NotNull private final WslDistributionDescriptor myDescriptor;
  @NotNull private final Path myExecutablePath;

  protected WSLDistribution(@NotNull WSLDistribution dist) {
    this(dist.myDescriptor, dist.myExecutablePath);
  }

  WSLDistribution(@NotNull WslDistributionDescriptor descriptor, @NotNull Path executablePath) {
    myDescriptor = descriptor;
    myExecutablePath = executablePath;
  }

  /**
   * @return executable file
   */
  @NotNull
  public Path getExecutablePath() {
    return myExecutablePath;
  }

  /**
   * @return identification data of WSL distribution.
   */
  public @Nullable @NlsSafe String readReleaseInfo() {
    try {
      final String key = "PRETTY_NAME";
      final String releaseInfo = "/etc/os-release"; // available for all distributions
      final ProcessOutput output = executeOnWsl(10000, "cat", releaseInfo);
      if (LOG.isDebugEnabled()) LOG.debug("Reading release info: " + getId());
      if (!output.checkSuccess(LOG)) return null;
      for (String line : output.getStdoutLines(true)) {
        if (line.startsWith(key) && line.length() >= (key.length() + 1)) {
          final String prettyName = line.substring(key.length() + 1);
          return  StringUtil.nullize(StringUtil.unquoteString(prettyName));
        }
      }
    }
    catch (ExecutionException e) {
      LOG.warn(e);
    }
    return null;
  }

  /**
   * @return creates and patches command line, e.g:
   * {@code ruby -v} => {@code bash -c "ruby -v"}
   */
  public @NotNull GeneralCommandLine createWslCommandLine(String @NotNull ... command) {
    return patchCommandLine(new GeneralCommandLine(command), null, new WSLCommandLineOptions());
  }

  /**
   * Creates a patched command line, executes it on wsl distribution and returns output
   *
   * @param command                linux command, eg {@code gem env}
   * @param options                {@link WSLCommandLineOptions} instance
   * @param timeout                timeout in ms
   * @param processHandlerConsumer consumes process handler just before execution, may be used for cancellation
   */
  @NotNull
  public ProcessOutput executeOnWsl(@NotNull List<String> command,
                                    @NotNull WSLCommandLineOptions options,
                                    int timeout,
                                    @Nullable Consumer<? super ProcessHandler> processHandlerConsumer) throws ExecutionException {
    GeneralCommandLine commandLine = patchCommandLine(new GeneralCommandLine(command), null, options);
    CapturingProcessHandler processHandler = new CapturingProcessHandler(commandLine);
    if (processHandlerConsumer != null) {
      processHandlerConsumer.consume(processHandler);
    }
    //noinspection deprecation
    return WSLUtil.addInputCloseListener(processHandler).runProcess(timeout);
  }

  public @NotNull ProcessOutput executeOnWsl(int timeout, @NonNls String @NotNull ... command) throws ExecutionException {
    return executeOnWsl(Arrays.asList(command), new WSLCommandLineOptions(), timeout, null);
  }

  /**
   * Copying changed files recursively from wslPath/ to windowsPath/; with rsync
   *
   * @param wslPath           source path inside wsl, e.g. /usr/bin
   * @param windowsPath       target windows path, e.g. C:/tmp; Directory going to be created
   * @param additionalOptions may be used for --delete (not recommended), --include and so on
   * @param handlerConsumer   consumes process handler just before execution. Can be used for fast cancellation
   * @return process output
   */

  @SuppressWarnings("UnusedReturnValue")
  public ProcessOutput copyFromWsl(@NotNull String wslPath,
                                   @NotNull String windowsPath,
                                   @Nullable List<String> additionalOptions,
                                   @Nullable Consumer<? super ProcessHandler> handlerConsumer
  )
    throws ExecutionException {
    //noinspection ResultOfMethodCallIgnored
    new File(windowsPath).mkdirs();
    List<String> command = new ArrayList<>(Arrays.asList("rsync", "-cr"));

    if (additionalOptions != null) {
      command.addAll(additionalOptions);
    }

    command.add(wslPath + "/");
    String targetWslPath = getWslPath(windowsPath);
    if (targetWslPath == null) {
      throw new ExecutionException(IdeBundle.message("wsl.rsync.unable.to.copy.files.dialog.message", windowsPath));
    }
    command.add(targetWslPath + "/");
    return executeOnWsl(command, new WSLCommandLineOptions(), -1, handlerConsumer);
  }

  /**
   * @deprecated use {@link #patchCommandLine(GeneralCommandLine, Project, WSLCommandLineOptions)} instead
   */
  @Deprecated
  @ApiStatus.ScheduledForRemoval(inVersion = "2021.3")
  @NotNull
  public <T extends GeneralCommandLine> T patchCommandLine(@NotNull T commandLine,
                                                           @Nullable Project project,
                                                           @Nullable String remoteWorkingDir,
                                                           boolean askForSudo) {
    WSLCommandLineOptions options = new WSLCommandLineOptions()
      .setRemoteWorkingDirectory(remoteWorkingDir)
      .setSudo(askForSudo);
    return patchCommandLine(commandLine, project, options);
  }

  /**
   * Patches passed command line to make it runnable in WSL context, e.g changes {@code date} to {@code ubuntu run "date"}.<p/>
   * <p>
   * Environment variables and working directory are mapped to the chain calls: working dir using {@code cd} and environment variables using {@code export},
   * e.g {@code bash -c "export var1=val1 && export var2=val2 && cd /some/working/dir && date"}.<p/>
   * <p>
   * Method should properly handle quotation and escaping of the environment variables.<p/>
   *
   * @param commandLine      command line to patch
   * @param project          current project
   * @param options          {@link WSLCommandLineOptions} instance
   * @param <T>              GeneralCommandLine or descendant
   * @return original {@code commandLine}, prepared to run in WSL context
   */
  @NotNull
  public <T extends GeneralCommandLine> T patchCommandLine(@NotNull T commandLine,
                                                           @Nullable Project project,
                                                           @NotNull WSLCommandLineOptions options) {
    logCommandLineBefore(commandLine, options);
    Path wslExe = findWslExe(options);
    boolean executeCommandInShell = wslExe == null || options.isExecuteCommandInShell();
    List<String> linuxCommand = buildLinuxCommand(commandLine, executeCommandInShell);

    if (options.isSudo()) { // fixme shouldn't we sudo for every chunk? also, preserve-env, login?
      prependCommand(linuxCommand, "sudo", "-S", "-p", "''");
      //TODO[traff]: ask password only if it is needed. When user is logged as root, password isn't asked.

      SUDO_LISTENER_KEY.set(commandLine, new ProcessAdapter() {
        @Override
        public void startNotified(@NotNull ProcessEvent event) {
          OutputStream input = event.getProcessHandler().getProcessInput();
          if (input == null) {
            return;
          }
          String password = CredentialPromptDialog.askPassword(
            project,
            IdeBundle.message("wsl.enter.root.password.dialog.title"),
            IdeBundle.message("wsl.sudo.password.for.root.label", getPresentableName()),
            new CredentialAttributes("WSL", "root", WSLDistribution.class),
            true
          );
          if (password != null) {
            try (PrintWriter pw = new PrintWriter(input, false, commandLine.getCharset())) {
              pw.println(password);
            }
          }
          else {
            // fixme notify user?
          }
          super.startNotified(event);
        }
      });
    }

    if (executeCommandInShell && StringUtil.isNotEmpty(options.getRemoteWorkingDirectory())) {
      prependCommand(linuxCommand, "cd", CommandLineUtil.posixQuote(options.getRemoteWorkingDirectory()), "&&");
    }
    if (executeCommandInShell) {
      commandLine.getEnvironment().forEach((key, val) -> {
        prependCommand(linuxCommand, "export", CommandLineUtil.posixQuote(key) + "=" + CommandLineUtil.posixQuote(val), "&&");
      });
      commandLine.getEnvironment().clear();
    }
    else {
      setWSLENV(commandLine);
    }

    commandLine.getParametersList().clearAll();
    String linuxCommandStr = StringUtil.join(linuxCommand, " ");
    if (wslExe != null) {
      commandLine.setExePath(wslExe.toString());
      commandLine.addParameters("--distribution", getMsId());
      if (options.isExecuteCommandInShell()) {
        commandLine.addParameters("--exec", "/bin/sh", "-c", linuxCommandStr);
      }
      else {
        commandLine.addParameter("--exec");
        commandLine.addParameters(linuxCommand);
      }
    }
    else {
      commandLine.setExePath(getExecutablePath().toString());
      commandLine.addParameter(getRunCommandLineParameter());
      commandLine.addParameter(linuxCommandStr);
    }

    logCommandLineAfter(commandLine);
    return commandLine;
  }

  private void logCommandLineBefore(@NotNull GeneralCommandLine commandLine, @NotNull WSLCommandLineOptions options) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("[" + getId() + "] " +
                "Patching: " +
                commandLine.getCommandLineString() +
                "; options: " +
                options +
                "; envs: " + commandLine.getEnvironment()
      );
    }
  }

  private void logCommandLineAfter(@NotNull GeneralCommandLine commandLine) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("[" + getId() + "] " + "Patched as: " + commandLine.getCommandLineList(null));
    }
  }

  private static @Nullable Path findWslExe(@NotNull WSLCommandLineOptions options) {
    File file = options.isLaunchWithWslExe() ? PathEnvironmentVariableUtil.findInPath("wsl.exe") : null;
    return file != null ? file.toPath() : null;
  }

  private static @NotNull List<String> buildLinuxCommand(@NotNull GeneralCommandLine commandLine, boolean executeCommandInShell) {
    List<String> command = ContainerUtil.concat(Collections.singletonList(commandLine.getExePath()), commandLine.getParametersList().getList());
    return new ArrayList<>(ContainerUtil.map(command, executeCommandInShell ? CommandLineUtil::posixQuote : Functions.identity()));
  }

  // https://blogs.msdn.microsoft.com/commandline/2017/12/22/share-environment-vars-between-wsl-and-windows/
  private static void setWSLENV(@NotNull GeneralCommandLine commandLine) {
    StringBuilder builder = new StringBuilder();
    for (String envName : commandLine.getEnvironment().keySet()) {
      if (StringUtil.isNotEmpty(envName)) {
        if (builder.length() > 0) {
          builder.append(":");
        }
        builder.append(envName).append("/u");
      }
    }
    if (builder.length() > 0) {
      String prevValue = commandLine.getEnvironment().get(WSLENV);
      if (prevValue == null) {
        prevValue = commandLine.getParentEnvironment().get(WSLENV);
      }
      String value = prevValue != null ? StringUtil.trimEnd(prevValue, ':') + ':' + builder
                                       : builder.toString();
      commandLine.getEnvironment().put(WSLENV, value);
    }
  }

  protected @NotNull @NlsSafe String getRunCommandLineParameter() {
    return RUN_PARAMETER;
  }

  /**
   * Attempts to resolve symlink with a given timeout
   *
   * @param path                  path in question
   * @param timeoutInMilliseconds timeout for execution
   * @return actual file name
   */
  public @NotNull @NlsSafe String resolveSymlink(@NotNull String path, int timeoutInMilliseconds) {

    try {
      final ProcessOutput output = executeOnWsl(timeoutInMilliseconds, "readlink", "-f", path);
      if (output.getExitCode() == 0) {
        String stdout = output.getStdout().trim();
        if (output.getExitCode() == 0 && StringUtil.isNotEmpty(stdout)) {
          return stdout;
        }
      }
    }
    catch (ExecutionException e) {
      LOG.debug("Error while resolving symlink: " + path, e);
    }
    return path;
  }

  public @NotNull @NlsSafe String resolveSymlink(@NotNull String path) {
    return resolveSymlink(path, RESOLVE_SYMLINK_TIMEOUT);
  }

  /**
   * Patches process handler with sudo listener, asking user for the password
   *
   * @param commandLine    patched command line
   * @param processHandler process handler, created from patched commandline
   * @return passed processHandler, patched with sudo listener if any
   */
  @NotNull
  public <T extends ProcessHandler>T patchProcessHandler(@NotNull GeneralCommandLine commandLine, @NotNull T processHandler) {
    ProcessListener listener = SUDO_LISTENER_KEY.get(commandLine);
    if (listener != null) {
      processHandler.addProcessListener(listener);
      SUDO_LISTENER_KEY.set(commandLine, null);
    }
    return processHandler;
  }

  /**
   * @return environment map of the default user in wsl
   */
  @NotNull
  public Map<String, String> getEnvironment() {
    try {
      ProcessOutput processOutput = executeOnWsl(5000, "env");
      Map<String, String> result = new HashMap<>();
      for (String string : processOutput.getStdoutLines()) {
        int assignIndex = string.indexOf('=');
        if (assignIndex == -1) {
          result.put(string, "");
        }
        else {
          result.put(string.substring(0, assignIndex), string.substring(assignIndex + 1));
        }
      }
      return result;
    }
    catch (ExecutionException e) {
      LOG.warn(e);
    }

    return Collections.emptyMap();
  }

  /**
   * @return Windows-dependent path for a file, pointed by {@code wslPath} in WSL or null if path is unmappable
   */

  public @Nullable @NlsSafe String getWindowsPath(@NotNull String wslPath) {
    return WSLUtil.getWindowsPath(wslPath, getMntRoot());
  }

  /**
   * @return Linux path for a file pointed by {@code windowsPath} or null if unavailable, like \\MACHINE\path
   */
  public @Nullable @NlsSafe String getWslPath(@NotNull String windowsPath) {
    //noinspection deprecation
    if (FileUtil.isWindowsAbsolutePath(windowsPath)) { // absolute windows path => /mnt/disk_letter/path
      return getMntRoot() + convertWindowsPath(windowsPath);
    }
    return null;
  }

  /**
   * @see WslDistributionDescriptor#getMntRoot()
   */
  public final @NotNull @NlsSafe String getMntRoot(){
    return myDescriptor.getMntRoot();
  }

  /**
   * @param windowsAbsolutePath properly formatted windows local absolute path: {@code drive:\path}
   * @return windows path converted to the linux path according to wsl rules: {@code c:\some\path} => {@code c/some/path}
   */
  static @NotNull @NlsSafe String convertWindowsPath(@NotNull String windowsAbsolutePath) {
    return Character.toLowerCase(windowsAbsolutePath.charAt(0)) + FileUtil.toSystemIndependentName(windowsAbsolutePath.substring(2));
  }

  public @NotNull @NlsSafe String getId() {
    return myDescriptor.getId();
  }

  public @NotNull @NlsSafe String getMsId() {
    return myDescriptor.getMsId();
  }

  public @NotNull @NlsSafe String getPresentableName() {
    return myDescriptor.getPresentableName();
  }

  @Override
  public String toString() {
    return "WSLDistribution{" +
           "myDescriptor=" + myDescriptor +
           '}';
  }

  private static void prependCommand(@NotNull List<String> command, String @NotNull ... commandToPrepend) {
    command.addAll(0, Arrays.asList(commandToPrepend));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    WSLDistribution that = (WSLDistribution)o;

    if (!myDescriptor.equals(that.myDescriptor)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return myDescriptor.hashCode();
  }

  /**
   * @return UNC root for the distribution, e.g. {@code \\wsl$\Ubuntu}
   */
  @ApiStatus.Experimental
  @NotNull
  public File getUNCRoot() {
    return new File(UNC_PREFIX + myDescriptor.getMsId());
  }

  /**
   * @return UNC root for the distribution, e.g. {@code \\wsl$\Ubuntu}
   * @see VfsUtil#findFileByIoFile(File, boolean)
   * @implNote there is a hack in {@link LocalFileSystemBase#getAttributes(VirtualFile)} which causes all network
   * virtual files to exists all the time. So we need to check explicitly that root exists. After implementing proper non-blocking check
   * for the network resource availability, this method may be simplified to findFileByIoFile
   */
  @ApiStatus.Experimental
  @Nullable
  public VirtualFile getUNCRootVirtualFile(boolean refreshIfNeed) {
    if (!Experiments.getInstance().isFeatureEnabled("wsl.p9.support")) {
      return null;
    }
    File uncRoot = getUNCRoot();
    return uncRoot.exists() ? VfsUtil.findFileByIoFile(uncRoot, refreshIfNeed) : null;
  }
}

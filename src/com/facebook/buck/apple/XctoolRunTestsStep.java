/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Runs {@code xctool} on one or more logic tests and/or application tests (each paired with a host
 * application).
 *
 * <p>The output is written in streaming JSON format to stdout and is parsed by {@link
 * XctoolOutputParsing}.
 */
class XctoolRunTestsStep implements Step {

  private static final Semaphore stutterLock = new Semaphore(1);
  private static final ScheduledExecutorService stutterTimeoutExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private static final String XCTOOL_ENV_VARIABLE_PREFIX = "XCTOOL_TEST_ENV_";
  private static final String FB_REFERENCE_IMAGE_DIR = "FB_REFERENCE_IMAGE_DIR";
  private static final Locale LOCALE = Locale.US;

  private final ProjectFilesystem filesystem;
  private final boolean withDownwardApi;

  public interface StdoutReadingCallback {
    void readStdout(InputStream stdout) throws IOException;
  }

  private static final Logger LOG = Logger.get(XctoolRunTestsStep.class);

  private final ImmutableList<String> command;
  private final ImmutableMap<String, String> environmentOverrides;
  private final Optional<Long> xctoolStutterTimeout;
  private final Path outputPath;
  private final Optional<? extends StdoutReadingCallback> stdoutReadingCallback;
  private final Optional<AppleDeveloperDirectoryForTestsProvider>
      appleDeveloperDirectoryForTestsProvider;
  private final TestSelectorList testSelectorList;
  private final Optional<String> logDirectoryEnvironmentVariable;
  private final Optional<Path> logDirectory;
  private final Optional<String> logLevelEnvironmentVariable;
  private final Optional<String> logLevel;
  private final Optional<Long> timeoutInMs;
  private final Optional<String> snapshotReferenceImagesPath;
  private final Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths;
  private final boolean isUsingXCodeBuildTool;

  // Helper class to parse the output of `xctool -listTestsOnly` then
  // store it in a multimap of {target: [testDesc1, testDesc2, ...], ... } pairs.
  //
  // We need to remember both the target name and the test class/method names, since
  // `xctool -only` requires the format `TARGET:className/methodName,...`
  private static class ListTestsOnlyHandler implements XctoolOutputParsing.XctoolEventCallback {
    private @Nullable String currentTestTarget;
    public Multimap<String, TestDescription> testTargetsToDescriptions;

    public ListTestsOnlyHandler() {
      this.currentTestTarget = null;
      // We use a LinkedListMultimap to make the order deterministic for testing.
      this.testTargetsToDescriptions = LinkedListMultimap.create();
    }

    @Override
    public void handleBeginOcunitEvent(XctoolOutputParsing.BeginOcunitEvent event) {
      // Signals the start of listing all tests belonging to a single target.
      this.currentTestTarget = event.targetName;
    }

    @Override
    public void handleEndOcunitEvent(XctoolOutputParsing.EndOcunitEvent event) {
      Objects.requireNonNull(this.currentTestTarget);
      Preconditions.checkState(this.currentTestTarget.equals(event.targetName));
      // Signals the end of listing all tests belonging to a single target.
      this.currentTestTarget = null;
    }

    @Override
    public void handleBeginTestSuiteEvent(XctoolOutputParsing.BeginTestSuiteEvent event) {}

    @Override
    public void handleEndTestSuiteEvent(XctoolOutputParsing.EndTestSuiteEvent event) {}

    @Override
    public void handleBeginStatusEvent(XctoolOutputParsing.StatusEvent event) {}

    @Override
    public void handleEndStatusEvent(XctoolOutputParsing.StatusEvent event) {}

    @Override
    public void handleBeginTestEvent(XctoolOutputParsing.BeginTestEvent event) {
      testTargetsToDescriptions.put(
          Objects.requireNonNull(this.currentTestTarget),
          new TestDescription(
              Objects.requireNonNull(event.className), Objects.requireNonNull(event.methodName)));
    }

    @Override
    public void handleEndTestEvent(XctoolOutputParsing.EndTestEvent event) {}
  }

  public XctoolRunTestsStep(
      ProjectFilesystem filesystem,
      Path xctoolPath,
      ImmutableMap<String, String> environmentOverrides,
      Optional<Long> xctoolStutterTimeout,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      Path outputPath,
      Optional<? extends StdoutReadingCallback> stdoutReadingCallback,
      Optional<AppleDeveloperDirectoryForTestsProvider> appleDeveloperDirectoryForTestsProvider,
      TestSelectorList testSelectorList,
      boolean waitForDebugger,
      Optional<String> logDirectoryEnvironmentVariable,
      Optional<Path> logDirectory,
      Optional<String> logLevelEnvironmentVariable,
      Optional<String> logLevel,
      Optional<Long> timeoutInMs,
      Optional<String> snapshotReferenceImagesPath,
      boolean withDownwardApi) {
    this.withDownwardApi = withDownwardApi;
    Preconditions.checkArgument(
        !(logicTestBundlePaths.isEmpty()
            && appTestBundleToHostAppPaths.isEmpty()
            && appTestPathsToTestHostAppPathsToTestTargetAppPaths.isEmpty()),
        "Either logic tests (%s) or app tests (%s) or uitest (%s) must be present",
        logicTestBundlePaths,
        appTestBundleToHostAppPaths,
        appTestPathsToTestHostAppPathsToTestTargetAppPaths);

    this.filesystem = filesystem;

    this.command =
        createCommandArgs(
            xctoolPath,
            sdkName,
            destinationSpecifier,
            logicTestBundlePaths,
            appTestBundleToHostAppPaths,
            appTestPathsToTestHostAppPathsToTestTargetAppPaths,
            waitForDebugger);
    this.appTestPathsToTestHostAppPathsToTestTargetAppPaths =
        appTestPathsToTestHostAppPathsToTestTargetAppPaths;
    this.environmentOverrides = environmentOverrides;
    this.xctoolStutterTimeout = xctoolStutterTimeout;
    this.outputPath = outputPath;
    this.stdoutReadingCallback = stdoutReadingCallback;
    this.appleDeveloperDirectoryForTestsProvider = appleDeveloperDirectoryForTestsProvider;
    this.testSelectorList = testSelectorList;
    this.logDirectoryEnvironmentVariable = logDirectoryEnvironmentVariable;
    this.logDirectory = logDirectory;
    this.logLevelEnvironmentVariable = logLevelEnvironmentVariable;
    this.logLevel = logLevel;
    this.timeoutInMs = timeoutInMs;
    this.snapshotReferenceImagesPath = snapshotReferenceImagesPath;
    // Super hacky, but xcodebuildtool is an alternative wrapper
    // around xcodebuild and forwarding the -f arguments only makes
    // sense in that context.
    this.isUsingXCodeBuildTool = xctoolPath.endsWith("xcodebuildtool.py");
  }

  @Override
  public String getShortName() {
    return "xctool-run-tests";
  }

  public ImmutableMap<String, String> getEnv(IsolatedExecutionContext context) {
    Map<String, String> environment = new HashMap<>(context.getEnvironment());
    Optional<Path> xcodeDeveloperDir =
        appleDeveloperDirectoryForTestsProvider.map(
            AppleDeveloperDirectoryForTestsProvider::getAppleDeveloperDirectoryForTests);
    xcodeDeveloperDir.ifPresent(path -> environment.put("DEVELOPER_DIR", path.toString()));
    // xctool will only pass through to the test environment variables whose names
    // start with `XCTOOL_TEST_ENV_`. (It will remove that prefix when passing them
    // to the test.)
    if (logDirectoryEnvironmentVariable.isPresent() && logDirectory.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logDirectoryEnvironmentVariable.get(),
          logDirectory.get().toString());
    }
    if (logLevelEnvironmentVariable.isPresent() && logLevel.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + logLevelEnvironmentVariable.get(), logLevel.get());
    }
    if (snapshotReferenceImagesPath.isPresent()) {
      environment.put(
          XCTOOL_ENV_VARIABLE_PREFIX + FB_REFERENCE_IMAGE_DIR, snapshotReferenceImagesPath.get());
    }

    environment.putAll(this.environmentOverrides);
    return ImmutableMap.copyOf(environment);
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableMap<String, String> env = getEnv(context);

    ProcessExecutorParams.Builder processExecutorParamsBuilder =
        ProcessExecutorParams.builder()
            .addAllCommand(command)
            .setDirectory(filesystem.getRootPath().getPath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .setEnvironment(env);

    Console console = context.getConsole();
    ProcessExecutor processExecutor = context.getProcessExecutor();
    if (withDownwardApi) {
      processExecutor = context.getDownwardApiProcessExecutor(processExecutor);
    }
    if (!testSelectorList.isEmpty()) {
      ImmutableList.Builder<String> xctoolFilterParamsBuilder = ImmutableList.builder();
      if (isUsingXCodeBuildTool) {
        int returnCode =
            formatXctoolParamsForXCodeBuildTool(
                console,
                appTestPathsToTestHostAppPathsToTestTargetAppPaths,
                testSelectorList,
                xctoolFilterParamsBuilder);
        if (returnCode != 0) {
          console.printErrorText("Failed to parse the selectors for xcodebuildtool.");
          return StepExecutionResult.of(returnCode);
        }
      } else {
        int returnCode =
            listAndFilterTestsThenFormatXctoolParams(
                processExecutor,
                console,
                testSelectorList,
                // Copy the entire xctool command and environment but add a -listTestsOnly arg.
                ProcessExecutorParams.builder()
                    .from(processExecutorParamsBuilder.build())
                    .addCommand("-listTestsOnly")
                    .build(),
                xctoolFilterParamsBuilder);
        if (returnCode != 0) {
          console.printErrorText("Failed to query tests with xctool");
          return StepExecutionResult.of(returnCode);
        }
      }
      ImmutableList<String> xctoolFilterParams = xctoolFilterParamsBuilder.build();
      if (xctoolFilterParams.isEmpty()) {
        console.printBuildFailure(
            String.format(
                LOCALE,
                "No tests found matching specified filter (%s)",
                testSelectorList.getExplanation()));
        return StepExecutionResults.SUCCESS;
      }
      processExecutorParamsBuilder.addAllCommand(xctoolFilterParams);
    }

    ProcessExecutorParams processExecutorParams = processExecutorParamsBuilder.build();

    // Only launch one instance of xctool at the time
    AtomicBoolean stutterLockIsNotified = new AtomicBoolean(false);
    try {
      LOG.debug("Running command: %s", processExecutorParams);

      acquireStutterLock(stutterLockIsNotified);

      long timeoutInMillis = timeoutInMs.orElse(1000L);

      // Start the process.
      int exitCode;
      String stderr;
      LaunchedProcess launchedProcess = null;
      try (LaunchedProcess ignore =
          launchedProcess = processExecutor.launchProcess(processExecutorParams)) {
        ProcessStdoutReader stdoutReader = new ProcessStdoutReader(launchedProcess);
        ProcessStderrReader stderrReader = new ProcessStderrReader(launchedProcess);

        ExecutorService executorService =
            MostExecutors.newMultiThreadExecutor(
                getClass().getSimpleName() + "_process_output_readers", 2);
        Future<?> stdoutFuture = executorService.submit(stdoutReader);
        Future<?> stderrFuture = executorService.submit(stderrReader);

        exitCode = waitForProcessAndGetExitCode(processExecutor, launchedProcess, timeoutInMs);
        processOutputStreams(executorService, timeoutInMillis, stdoutFuture, stderrFuture);

        Optional<IOException> exception = stdoutReader.getException();
        if (exception.isPresent()) {
          throw exception.get();
        }
        stderr = stderrReader.getStdErr();
        LOG.debug("Finished running command, exit code %d, stderr %s", exitCode, stderr);
      } finally {
        if (launchedProcess != null) {
          processExecutor.waitForLaunchedProcess(launchedProcess);
        }
      }

      if (exitCode != StepExecutionResults.SUCCESS_EXIT_CODE) {
        String errorMessage;
        if (stderr.isEmpty()) {
          errorMessage = String.format(LOCALE, "xctool failed with exit code %d", exitCode);
        } else {
          errorMessage =
              String.format(LOCALE, "xctool failed with exit code %d: %s", exitCode, stderr);
        }
        console.printErrorText(errorMessage);
      }

      return StepExecutionResult.builder()
          .setExitCode(exitCode)
          .setExecutedCommand(launchedProcess.getCommand())
          .setStderr(Optional.ofNullable(stderr))
          .build();
    } finally {
      releaseStutterLock(stutterLockIsNotified);
    }
  }

  private void processOutputStreams(
      ExecutorService executorService,
      long timeoutInMillis,
      Future<?> stdoutFuture,
      Future<?> stderrFuture)
      throws InterruptedException {

    try {
      stdoutFuture.get(timeoutInMillis, TimeUnit.MILLISECONDS);
      stderrFuture.get(timeoutInMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      LOG.warn(e.getCause(), "Exception during reading stdout and stderr streams");
    } catch (TimeoutException e) {
      LOG.warn("Timeout during reading stdout and stderr streams.");
    } finally {
      MostExecutors.shutdown(executorService, timeoutInMillis, TimeUnit.MILLISECONDS);
    }
  }

  private class ProcessStdoutReader implements Runnable {

    private final LaunchedProcess launchedProcess;
    private Optional<IOException> exception = Optional.empty();

    public ProcessStdoutReader(LaunchedProcess launchedProcess) {
      this.launchedProcess = launchedProcess;
    }

    @Override
    public void run() {
      try (OutputStream outputStream = filesystem.newFileOutputStream(outputPath);
          TeeInputStream stdoutWrapperStream =
              new TeeInputStream(launchedProcess.getStdout(), outputStream)) {
        if (stdoutReadingCallback.isPresent()) {
          // The caller is responsible for reading all the data, which TeeInputStream will
          // copy to outputStream.
          stdoutReadingCallback.get().readStdout(stdoutWrapperStream);
        } else {
          // Nobody's going to read from stdoutWrapperStream, so close it and copy
          // the process's stdout to outputPath directly.
          stdoutWrapperStream.close();
          ByteStreams.copy(launchedProcess.getStdout(), outputStream);
        }
      } catch (IOException e) {
        exception = Optional.of(e);
      }
    }

    public Optional<IOException> getException() {
      return exception;
    }
  }

  private static class ProcessStderrReader implements Runnable {

    private final LaunchedProcess launchedProcess;
    private String stderr = "";

    public ProcessStderrReader(LaunchedProcess launchedProcess) {
      this.launchedProcess = launchedProcess;
    }

    @Override
    public void run() {
      try (InputStreamReader stderrReader =
              new InputStreamReader(launchedProcess.getStderr(), StandardCharsets.UTF_8);
          BufferedReader bufferedStderrReader = new BufferedReader(stderrReader)) {
        stderr = CharStreams.toString(bufferedStderrReader).trim();
      } catch (IOException e) {
        stderr = Throwables.getStackTraceAsString(e);
      }
    }

    public String getStdErr() {
      return stderr;
    }
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return command.stream().map(Escaper.SHELL_ESCAPER).collect(Collectors.joining(" "));
  }

  private static int formatXctoolParamsForXCodeBuildTool(
      Console console,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      TestSelectorList testSelectorList,
      ImmutableList.Builder<String> filterParamsBuilder) {
    LOG.debug("Filtering tests with selector list: %s", testSelectorList.getExplanation());
    for (String selector : testSelectorList.getRawSelectors()) {
      String flag = "-only";
      if (selector.charAt(0) == '!') {
        flag = "-omit";
        selector = selector.substring(1);
      }
      String[] split = selector.split("#");
      String className = "";
      String methodName = "";
      if (split.length == 1) {
        // "No #, implies this is a class name";
        className = split[0];
      } else if (split.length == 2) {
        className = split[0];
        methodName = split[1];
      } else {
        console.printErrorText(selector + " is not a valid selector for xcodebuildtool.");
        return 1;
      }
      if (className.endsWith("$")) {
        className = className.substring(0, className.length() - 1);
      }
      if (methodName.endsWith("$")) {
        methodName = methodName.substring(0, methodName.length() - 1);
      }
      for (Map.Entry<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetApp :
          appTestPathsToTestHostAppPathsToTestTargetAppPaths.entrySet()) {
        filterParamsBuilder.add(flag);
        Path suite = appTestPathsToTestHostAppPathsToTestTargetApp.getKey();
        StringBuilder sb = new StringBuilder();
        String fileName = suite.getFileName().toString();
        int extensionPosition = fileName.lastIndexOf(".");
        if (extensionPosition == -1) {
          console.printErrorText(selector + " is not a valid selector for xcodebuildtool.");
          return 1;
        } else {
          sb.append(fileName, 0, extensionPosition);
        }
        sb.append("/");
        sb.append(className);
        if (!methodName.isEmpty()) {
          sb.append("/");
          sb.append(methodName);
        }
        LOG.debug("Selector %s was translated to filter %s", selector, sb.toString());
        filterParamsBuilder.add(sb.toString());
      }
    }
    return 0;
  }

  private static int listAndFilterTestsThenFormatXctoolParams(
      ProcessExecutor processExecutor,
      Console console,
      TestSelectorList testSelectorList,
      ProcessExecutorParams listTestsOnlyParams,
      ImmutableList.Builder<String> filterParamsBuilder)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(!testSelectorList.isEmpty());
    LOG.debug("Filtering tests with selector list: %s", testSelectorList.getExplanation());

    LOG.debug("Listing tests with command: %s", listTestsOnlyParams);
    try (LaunchedProcess launchedProcess = processExecutor.launchProcess(listTestsOnlyParams)) {

      ListTestsOnlyHandler listTestsOnlyHandler = new ListTestsOnlyHandler();
      String stderr;
      int listTestsResult;
      try (InputStreamReader isr =
              new InputStreamReader(launchedProcess.getStdout(), StandardCharsets.UTF_8);
          BufferedReader br = new BufferedReader(isr);
          InputStreamReader esr =
              new InputStreamReader(launchedProcess.getStderr(), StandardCharsets.UTF_8);
          BufferedReader ebr = new BufferedReader(esr)) {
        stderr = CharStreams.toString(ebr).trim();
        XctoolOutputParsing.streamOutputFromReader(br, listTestsOnlyHandler);
        listTestsResult = processExecutor.waitForLaunchedProcess(launchedProcess).getExitCode();
      }

      if (listTestsResult != 0) {
        if (!stderr.isEmpty()) {
          console.printErrorText(
              String.format(
                  LOCALE, "xctool failed with exit code %d: %s", listTestsResult, stderr));
        } else {
          console.printErrorText(
              String.format(LOCALE, "xctool failed with exit code %d", listTestsResult));
        }
      } else {
        formatXctoolFilterParams(
            testSelectorList, listTestsOnlyHandler.testTargetsToDescriptions, filterParamsBuilder);
      }

      return listTestsResult;
    }
  }

  private static void formatXctoolFilterParams(
      TestSelectorList testSelectorList,
      Multimap<String, TestDescription> testTargetsToDescriptions,
      ImmutableList.Builder<String> filterParamsBuilder) {
    for (String testTarget : testTargetsToDescriptions.keySet()) {
      StringBuilder sb = new StringBuilder();
      boolean matched = false;
      for (TestDescription testDescription : testTargetsToDescriptions.get(testTarget)) {
        if (!testSelectorList.isIncluded(testDescription)) {
          continue;
        }
        if (!matched) {
          matched = true;
          sb.append(testTarget);
          sb.append(':');
        } else {
          sb.append(',');
        }
        sb.append(testDescription.getClassName());
        sb.append('/');
        sb.append(testDescription.getMethodName());
      }
      if (matched) {
        filterParamsBuilder.add("-only");
        filterParamsBuilder.add(sb.toString());
      }
    }
  }

  private static ImmutableList<String> createCommandArgs(
      Path xctoolPath,
      String sdkName,
      Optional<String> destinationSpecifier,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Map<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetAppPaths,
      boolean waitForDebugger) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(xctoolPath.toString());
    args.add("-reporter");
    args.add("json-stream");
    args.add("-sdk", sdkName);
    if (destinationSpecifier.isPresent()) {
      args.add("-destination");
      args.add(destinationSpecifier.get());
    }
    args.add("run-tests");
    for (Path logicTestBundlePath : logicTestBundlePaths) {
      args.add("-logicTest");
      args.add(logicTestBundlePath.toString());
    }
    for (Map.Entry<Path, Path> appTestBundleAndHostApp : appTestBundleToHostAppPaths.entrySet()) {
      args.add("-appTest");
      args.add(appTestBundleAndHostApp.getKey() + ":" + appTestBundleAndHostApp.getValue());
    }

    for (Map.Entry<Path, Map<Path, Path>> appTestPathsToTestHostAppPathsToTestTargetApp :
        appTestPathsToTestHostAppPathsToTestTargetAppPaths.entrySet()) {
      for (Map.Entry<Path, Path> testHostAppToTestTargetApp :
          appTestPathsToTestHostAppPathsToTestTargetApp.getValue().entrySet()) {
        args.add("-uiTest");
        args.add(
            appTestPathsToTestHostAppPathsToTestTargetApp.getKey()
                + ":"
                + testHostAppToTestTargetApp.getKey()
                + ":"
                + testHostAppToTestTargetApp.getValue());
      }
    }
    if (waitForDebugger) {
      args.add("-waitForDebugger");
    }

    return args.build();
  }

  private static int waitForProcessAndGetExitCode(
      ProcessExecutor processExecutor, LaunchedProcess launchedProcess, Optional<Long> timeoutInMs)
      throws InterruptedException {
    int processExitCode;
    if (timeoutInMs.isPresent()) {
      ProcessExecutor.Result processResult =
          processExecutor.waitForLaunchedProcessWithTimeout(
              launchedProcess, timeoutInMs.get(), Optional.empty());
      if (processResult.isTimedOut()) {
        throw new HumanReadableException(
            "Timed out after %d ms running test command", timeoutInMs.orElse(-1L));
      } else {
        processExitCode = processResult.getExitCode();
      }
    } else {
      processExitCode = processExecutor.waitForLaunchedProcess(launchedProcess).getExitCode();
    }
    if (processExitCode == 0 || processExitCode == 1) {
      // Test failure is denoted by xctool returning 1. Unfortunately, there's no way
      // to distinguish an internal xctool error from a test failure:
      //
      // https://github.com/facebook/xctool/issues/511
      //
      // We don't want to fail the step on a test failure, so return 0 on either
      // xctool exit code.
      return 0;
    } else {
      // Some unknown failure.
      return processExitCode;
    }
  }

  private void acquireStutterLock(AtomicBoolean stutterLockIsNotified) throws InterruptedException {
    if (!xctoolStutterTimeout.isPresent()) {
      return;
    }
    try {
      stutterLock.acquire();
    } catch (Exception e) {
      releaseStutterLock(stutterLockIsNotified);
      throw e;
    }
    stutterTimeoutExecutorService.schedule(
        () -> releaseStutterLock(stutterLockIsNotified),
        xctoolStutterTimeout.get(),
        TimeUnit.MILLISECONDS);
  }

  private void releaseStutterLock(AtomicBoolean stutterLockIsNotified) {
    if (!xctoolStutterTimeout.isPresent()) {
      return;
    }
    if (!stutterLockIsNotified.getAndSet(true)) {
      stutterLock.release();
    }
  }

  public ImmutableList<String> getCommand() {
    return command;
  }
}

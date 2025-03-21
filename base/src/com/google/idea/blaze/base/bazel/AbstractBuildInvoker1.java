/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.bazel;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.async.executor.BlazeExecutor;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeCommandRunner;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.scopes.TimingScope;
import com.google.idea.blaze.base.settings.BuildSystemName;
import com.google.idea.blaze.base.sync.SyncScope.SyncFailedException;
import com.intellij.openapi.project.Project;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Base class for implementations of {@link BuildInvoker} that provides getters and deals with
 * running `blaze info`.
 */
// TODO(b/374906681): Replace @link{AbstractBuildInvoker} and its usages by this class
public abstract class AbstractBuildInvoker1 implements BuildInvoker {
  protected final Project project;
  protected final BlazeContext blazeContext;
  private final String binaryPath;
  private BlazeInfo blazeInfo;

  public AbstractBuildInvoker1(Project project, BlazeContext blazeContext, String binaryPath) {
    this.project = project;
    this.blazeContext = blazeContext;
    this.binaryPath = binaryPath;
  }

  @Override
  public String getBinaryPath() {
    return this.binaryPath;
  }

  @Override
  @Nullable
  public synchronized BlazeInfo getBlazeInfo() throws SyncFailedException {
    if (blazeInfo == null) {
      blazeInfo = getBlazeInfoResult();
    }
    return blazeInfo;
  }

  private BlazeInfo getBlazeInfoResult() throws SyncFailedException {
    ListenableFuture<BlazeInfo> blazeInfoFuture;
    FutureUtil.FutureResult<BlazeInfo> result;
    blazeInfoFuture = Futures.transform(BlazeExecutor.getInstance().submit(() -> {
                                          try (InputStream stream = invokeInfo(BlazeCommand.builder(this, BlazeCommandName.INFO, project))) {
                                            return stream.readAllBytes();
                                          }
                                        }), bytes -> BlazeInfo.create(BuildSystemName.Blaze, parseBlazeInfoResult(new String(bytes, StandardCharsets.UTF_8).trim())),
                                        BlazeExecutor.getInstance().getExecutor());

    result =
      FutureUtil.waitForFuture(blazeContext, blazeInfoFuture).timed(BuildSystemName.Blaze + "Info", TimingScope.EventType.BlazeInvocation)
        .withProgressMessage(String.format("Running %s info...", BuildSystemName.Blaze))
        .onError(String.format("Could not run %s info", BuildSystemName.Blaze)).run();

    if (result.success()) {
      return result.result();
    }
    //TODO (b/374906681) Replace with BuildException once legacy sync is deprecated
    throw new SyncFailedException(String.format("Failed to run `%s info`", getBinaryPath()), result.exception());
  }

  @Override
  public BuildResultHelper createBuildResultHelper() {
    throw new UnsupportedOperationException("This method should not be called, this invoker does not support build result helpers.");
  }

  @Override
  public BlazeCommandRunner getCommandRunner() {
    throw new UnsupportedOperationException("This method should not be called, this invoker does not support command runners.");
  }

  @Override
  public boolean supportsHomeBlazerc() {
    throw new UnsupportedOperationException("This method should not be called, this invoker does not support this method.");
  }

  @Override
  public BuildSystem getBuildSystem() {
    throw new UnsupportedOperationException("This method should not be called, this invoker does not support this method.");
  }

  @Override
  public boolean supportsParallelism() {
    throw new UnsupportedOperationException("This method should not be called, this invoker does not support this method.");
  }

  private static ImmutableMap<String, String> parseBlazeInfoResult(String blazeInfoString) {
    ImmutableMap.Builder<String, String> blazeInfoMapBuilder = ImmutableMap.builder();
    String[] blazeInfoLines = blazeInfoString.split("\n");
    for (String blazeInfoLine : blazeInfoLines) {
      // Just split on the first ":".
      String[] keyValue = blazeInfoLine.split(":", 2);
      if (keyValue.length != 2) {
        // ignore any extraneous stdout
        continue;
      }
      String key = keyValue[0].trim();
      String value = keyValue[1].trim();
      blazeInfoMapBuilder.put(key, value);
    }
    return blazeInfoMapBuilder.build();
  }
}

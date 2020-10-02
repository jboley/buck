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

package com.facebook.buck.artifact_cache;

import build.bazel.remote.execution.v2.Digest;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.SamplingCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * SecondLevelArtifactCache implementation that will either defer to a given ArtifactCache OR make
 * calls to the CAS, depending on the given artifact or given content key.
 */
public class HybridCASSecondLevelArtifactCache implements SecondLevelArtifactCache {
  private static final Logger LOG = Logger.get(HybridCASSecondLevelArtifactCache.class);

  private final ArtifactCache delegate;
  private final ProjectFilesystem projectFilesystem;
  private final Optional<ContentAddressedStorageClient> casClient;
  private final Boolean enableWrite;
  private final int artifactPartitionWritePercentage;
  private final int artifactPartitionReadPercentage;

  private final SamplingCounter secondLevelHashComputationTimeMs;

  public HybridCASSecondLevelArtifactCache(
      ArtifactCache delegate,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      Optional<ContentAddressedStorageClient> casClient,
      Boolean enableWrite,
      int artifactPartitionWritePercentage,
      int artifactPartitionReadPercentage) {
    this.delegate = delegate;
    this.projectFilesystem = projectFilesystem;
    this.casClient = casClient;
    this.enableWrite = enableWrite;
    this.artifactPartitionWritePercentage = artifactPartitionWritePercentage;
    this.artifactPartitionReadPercentage = artifactPartitionReadPercentage;

    secondLevelHashComputationTimeMs =
        new SamplingCounter(
            TwoLevelArtifactCacheDecorator.COUNTER_CATEGORY,
            "second_level_hash_computation_time_ms",
            ImmutableMap.of());
    buckEventBus.post(
        new CounterRegistry.AsyncCounterRegistrationEvent(
            ImmutableList.of(secondLevelHashComputationTimeMs)));
  }

  private boolean shouldReadCAS(SecondLevelContentKey contentKey) {
    return casClient.isPresent()
        && contentKey.getType() == SecondLevelContentKey.Type.CAS_ONLY
        && (SecondLevelContentKey.getDigestHash(contentKey).hashCode() % 100)
            < artifactPartitionReadPercentage;
  }

  private Digest parseDigest(String s) {
    String[] parts = s.split(":", 2);
    return Digest.newBuilder().setHash(parts[0]).setSizeBytes(Long.parseLong(parts[1])).build();
  }

  private ListenableFuture<CacheResult> doCasFetchAsync(
      SecondLevelContentKey contentKey, LazyPath output) {
    if (!casClient.isPresent()) {
      throw new RuntimeException("doCasFetchAsync called, but cannot read from CAS!");
    }

    Digest digest;
    try {
      digest = parseDigest(contentKey.getKey());
    } catch (Exception e) {
      throw new HumanReadableException(
          e, "Unknown digest format for second-level content key (%s)", contentKey.toString());
    }

    return Futures.transform(
        casClient.get().fetch(new GrpcProtocol.GrpcDigest(digest)),
        (ByteBuffer buf) -> {
          try {
            projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
            Path tmp =
                projectFilesystem.createTempFile(
                    projectFilesystem.getBuckPaths().getScratchDir(),
                    "buckcache_artifact",
                    ".hybrid-cas.tmp");
            OutputStream tmpFile = projectFilesystem.newFileOutputStream(tmp);

            WritableByteChannel channel = Channels.newChannel(tmpFile);
            channel.write(buf);

            channel.close();
            tmpFile.close();

            projectFilesystem.move(tmp, output.get(), StandardCopyOption.REPLACE_EXISTING);
          } catch (Exception e) {
            return CacheResult.error("cas", ArtifactCacheMode.hybrid_thrift_grpc, e.getMessage());
          }

          return CacheResult.hit(
              "cas",
              ArtifactCacheMode.hybrid_thrift_grpc,
              ImmutableMap.<String, String>builder().build(),
              digest.getSizeBytes());
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, String contentKey, LazyPath output) {
    SecondLevelContentKey ck = SecondLevelContentKey.fromString(contentKey);
    LOG.verbose("Fetching content key %s [%s]", contentKey, ck.getType());

    if (shouldReadCAS(ck)) {
      return doCasFetchAsync(ck, output);
    }

    return delegate.fetchAsync(target, ck.toRuleKey(), output);
  }

  private boolean shouldWriteCAS(String digest) {
    return enableWrite
        && casClient.isPresent()
        && (digest.hashCode() % 100) < artifactPartitionWritePercentage;
  }

  private ListenableFuture<Unit> doCasStoreAsync(BorrowablePath output, Digest digest) {
    if (!enableWrite || !casClient.isPresent()) {
      throw new RuntimeException("doCasStoreAsync called, but cannot write to CAS!");
    }

    try {
      return casClient
          .get()
          .addMissing(
              Collections.singletonList(
                  UploadDataSupplier.of(
                      output.getPath().getFileName().toString(),
                      new GrpcProtocol.GrpcDigest(digest),
                      () -> projectFilesystem.newFileInputStream(output.getPath()))));
    } catch (IOException e) {
      LOG.error(e, "Error uploading artifact to CAS");
      return Futures.immediateCancelledFuture();
    }
  }

  @Override
  public ListenableFuture<String> storeAsync(ArtifactInfo info, BorrowablePath output) {
    Digest digest;
    try {
      digest = computeDigest(output);
    } catch (IOException e) {
      throw new RuntimeException("Cannot compute hash/size of " + output.getPath());
    }

    boolean shouldCAS = shouldWriteCAS(digest.getHash());
    LOG.verbose("Storing %s:%d (to cas? %s)", digest.getHash(), digest.getSizeBytes(), shouldCAS);

    SecondLevelContentKey contentKey =
        SecondLevelContentKey.builder()
            .setType(
                shouldCAS
                    ? SecondLevelContentKey.Type.CAS_ONLY
                    : SecondLevelContentKey.Type.CACHE_ONLY)
            .setKey(String.format("%s:%d", digest.getHash(), digest.getSizeBytes()))
            .build();

    return Futures.transform(
        shouldCAS
            ? doCasStoreAsync(output, digest)
            : delegate.store(
                ArtifactInfo.builder()
                    .addRuleKeys(contentKey.toRuleKey())
                    .setBuildTarget(info.getBuildTarget())
                    .setBuildTimeMs(info.getBuildTimeMs())
                    .build(),
                output),
        __ -> contentKey.toString(),
        MoreExecutors.directExecutor());
  }

  private Digest computeDigest(BorrowablePath output) throws IOException {
    long hashComputationStart = System.currentTimeMillis();

    Path p = output.getPath();
    String hashCode = projectFilesystem.computeSha1(p).toString();
    long fileSize = projectFilesystem.getFileSize(p);

    long hashComputationEnd = System.currentTimeMillis();
    secondLevelHashComputationTimeMs.addSample(hashComputationEnd - hashComputationStart);

    return Digest.newBuilder().setHash(hashCode).setSizeBytes(fileSize).build();
  }

  @Override
  public void close() {
    delegate.close();
  }
}

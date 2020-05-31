/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
package io.atomix.raft.snapshot.impl;

import static java.nio.file.StandardOpenOption.CREATE_NEW;

import io.atomix.raft.snapshot.PersistedSnapshot;
import io.atomix.raft.snapshot.ReceivedSnapshot;
import io.atomix.raft.snapshot.SnapshotChunk;
import io.zeebe.util.ChecksumUtil;
import io.zeebe.util.FileUtil;
import io.zeebe.util.ZbLogger;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

public class FileBasedReceivedSnapshot implements ReceivedSnapshot {

  private static final Logger LOGGER = new ZbLogger(FileBasedReceivedSnapshot.class);
  private static final boolean FAILED = false;
  private static final boolean SUCCESS = true;

  private final Path directory;
  private final FileBasedSnapshotStore snapshotStore;

  private ByteBuffer expectedId;
  private final FileBasedSnapshotMetadata metadata;
  private long expectedSnapshotChecksum;

  FileBasedReceivedSnapshot(
      final FileBasedSnapshotMetadata metadata,
      final Path directory,
      final FileBasedSnapshotStore snapshotStore) {
    this.metadata = metadata;
    this.snapshotStore = snapshotStore;
    this.directory = directory;
    this.expectedSnapshotChecksum = Long.MIN_VALUE;
  }

  @Override
  public long index() {
    return metadata.getIndex();
  }

  @Override
  public boolean containsChunk(final ByteBuffer chunkId) {
    return Files.exists(directory.resolve(getFile(chunkId)));
  }

  @Override
  public boolean isExpectedChunk(final ByteBuffer chunkId) {
    if (expectedId == null) {
      return chunkId == null;
    }

    return expectedId.equals(chunkId);
  }

  @Override
  public boolean apply(final SnapshotChunk snapshotChunk) throws IOException {
    final var currentSnapshotChecksum = snapshotChunk.getSnapshotChecksum();

    if (expectedSnapshotChecksum == Long.MIN_VALUE) {
      this.expectedSnapshotChecksum = currentSnapshotChecksum;
    }

    if (expectedSnapshotChecksum != currentSnapshotChecksum) {
      LOGGER.warn(
          "Expected snapshot chunk with equal snapshot checksum {}, but got chunk with snapshot checksum {}.",
          expectedSnapshotChecksum,
          currentSnapshotChecksum);
      return FAILED;
    }

    final String snapshotId = snapshotChunk.getSnapshotId();
    final String chunkName = snapshotChunk.getChunkName();

    if (snapshotStore.exists(snapshotId)) {
      LOGGER.debug(
          "Ignore snapshot snapshotChunk {}, because snapshot {} already exists.",
          chunkName,
          snapshotId);
      return SUCCESS;
    }

    final long expectedChecksum = snapshotChunk.getChecksum();
    final long actualChecksum = SnapshotChunkUtil.createChecksum(snapshotChunk.getContent());

    if (expectedChecksum != actualChecksum) {
      LOGGER.warn(
          "Expected to have checksum {} for snapshot chunk {} ({}), but calculated {}",
          expectedChecksum,
          chunkName,
          snapshotId,
          actualChecksum);
      return FAILED;
    }

    final var tmpSnapshotDirectory = directory;
    FileUtil.ensureDirectoryExists(tmpSnapshotDirectory);

    final var snapshotFile = tmpSnapshotDirectory.resolve(chunkName);
    if (Files.exists(snapshotFile)) {
      LOGGER.debug("Received a snapshot snapshotChunk which already exist '{}'.", snapshotFile);
      return FAILED;
    }

    LOGGER.debug("Consume snapshot snapshotChunk {} of snapshot {}", chunkName, snapshotId);
    return writeReceivedSnapshotChunk(snapshotChunk, snapshotFile);
  }

  private boolean writeReceivedSnapshotChunk(
      final SnapshotChunk snapshotChunk, final Path snapshotFile) throws IOException {
    Files.write(snapshotFile, snapshotChunk.getContent(), CREATE_NEW, StandardOpenOption.WRITE);
    LOGGER.trace("Wrote replicated snapshot chunk to file {}", snapshotFile);
    return SUCCESS;
  }

  @Override
  public void setNextExpected(final ByteBuffer nextChunkId) {
    expectedId = nextChunkId;
  }

  @Override
  public PersistedSnapshot persist() {
    final var files = directory.toFile().listFiles();
    Objects.requireNonNull(files);

    final var filePaths =
        Arrays.stream(files).sorted().map(File::toPath).collect(Collectors.toList());
    final long actualSnapshotChecksum;
    try {
      actualSnapshotChecksum = ChecksumUtil.createCombinedChecksum(filePaths);
    } catch (final IOException e) {
      throw new UncheckedIOException("Unexpected exception on calculating snapshot checksum.", e);
    }

    if (actualSnapshotChecksum != expectedSnapshotChecksum) {
      throw new IllegalStateException(
          String.format(
              "Expected snapshot checksum %d, but calculated %d.",
              expectedSnapshotChecksum, actualSnapshotChecksum));
    }

    return snapshotStore.newSnapshot(metadata, directory);
  }

  @Override
  public void abort() {
    try {
      LOGGER.error("DELETE dir {}", directory);
      FileUtil.deleteFolder(directory);
    } catch (final IOException e) {
      LOGGER.warn("Failed to delete pending snapshot {}", this, e);
    }
  }

  public Path getPath() {
    return directory;
  }

  private String getFile(final ByteBuffer chunkId) {
    final var view = new UnsafeBuffer(chunkId);
    return view.getStringWithoutLengthAscii(0, chunkId.remaining());
  }

  @Override
  public String toString() {
    return "FileBasedReceivedSnapshot{"
        + "directory="
        + directory
        + ", snapshotStore="
        + snapshotStore
        + ", expectedId="
        + expectedId
        + ", metadata="
        + metadata
        + '}';
  }
}
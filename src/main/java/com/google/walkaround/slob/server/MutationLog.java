/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.walkaround.slob.server;

import static com.google.common.base.Preconditions.checkNotNull;

import com.goodow.realtime.server.model.Delta;
import com.goodow.realtime.server.model.DeltaRejected;
import com.goodow.realtime.server.model.ObjectId;
import com.goodow.realtime.server.model.SessionId;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.api.datastore.Text;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.walkaround.slob.shared.InvalidSnapshot;
import com.google.walkaround.slob.shared.SlobModel;
import com.google.walkaround.slob.shared.SlobModel.ReadableSlob;
import com.google.walkaround.slob.shared.StateAndVersion;
import com.google.walkaround.util.server.RetryHelper.PermanentFailure;
import com.google.walkaround.util.server.RetryHelper.RetryableFailure;
import com.google.walkaround.util.server.appengine.CheckedDatastore;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedIterator;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedTransaction;
import com.google.walkaround.util.server.appengine.DatastoreUtil;
import com.google.walkaround.util.server.appengine.OversizedPropertyMover;
import com.google.walkaround.util.server.appengine.OversizedPropertyMover.MovableProperty;
import com.google.walkaround.util.shared.Assert;
import com.google.walkaround.util.shared.ConcatenatingList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Functionality for traversing and appending to the mutation log.
 * 
 * Use of only this class for mutating the log guarantees no corruption will occur.
 * 
 * XXX: Not necessarily thread safe, should only be used in a single-threaded fashion but let's make
 * it thread safe anyway just in case... (better yet, add assertions that it's used only from one
 * thread, so that we notice when it's not)
 */
public class MutationLog {

  /**
   * Extends the log by additional deltas. Automatically takes snapshots as needed.
   * 
   * Deltas and snapshots are staged in memory and added to the underlying transaction only when
   * {@link Appender#finish()} is called.
   */
  public class Appender {
    private final StateAndVersion state;
    private final List<DeltaEntry> stagedDeltaEntries = Lists.newArrayList();
    private final List<SnapshotEntry> stagedSnapshotEntries = Lists.newArrayList();
    private long estimatedBytesStaged = 0;
    private long mostRecentSnapshotBytes;
    private long totalDeltaBytesSinceSnapshot;
    private boolean finished = false;

    private Appender(StateAndVersion state, long mostRecentSnapshotBytes,
        long totalDeltaBytesSinceSnapshot) {
      this.state = state;
      this.mostRecentSnapshotBytes = mostRecentSnapshotBytes;
      this.totalDeltaBytesSinceSnapshot = totalDeltaBytesSinceSnapshot;
    }

    /**
     * Stages a delta for writing, verifying that it is valid (applies cleanly).
     */
    public void append(Delta<String> delta) throws DeltaRejected {
      checkNotFinished();
      appendAll(ImmutableList.of(delta));
    }

    /**
     * Stage deltas for writing, verifying that they are valid (apply cleanly). Will append whatever
     * prefix of {@deltas} is valid before throwing {@link DeltaRejected}.
     */
    public void appendAll(List<Delta<String>> deltas) throws DeltaRejected {
      checkNotFinished();
      if (deltas.isEmpty()) {
        // For now, this is a no-op; if we want this to potentially append a
        // snapshot, we'll need a justification for that.
        return;
      }
      deltas = ImmutableList.copyOf(deltas);
      for (Delta<String> delta : deltas) {
        long oldVersion = state.getVersion();
        state.apply(delta);
        DeltaEntry deltaEntry =
            new DeltaEntry(objectId, oldVersion, new Delta<String>(delta.getSessionId(), delta
                .getPayload()));
        stagedDeltaEntries.add(deltaEntry);
        long thisDeltaBytes = estimateSizeBytes(deltaEntry);
        estimatedBytesStaged += thisDeltaBytes;
        totalDeltaBytesSinceSnapshot += thisDeltaBytes;
      }

      // TODO(ohler): Avoid computing the snapshot every time since this is
      // costly. Add a size estimation to slob instead. We need this anyway to
      // implement size limits.
      SnapshotEntry snapshotEntry =
          new SnapshotEntry(objectId, state.getVersion(), state.getState().snapshot());
      long snapshotBytes = estimateSizeBytes(snapshotEntry);
      log.info("Object now at version " + state.getVersion() + "; snapshotBytes=" + snapshotBytes
          + ", mostRecentSnapshotBytes=" + mostRecentSnapshotBytes
          + ", totalDeltaBytesSinceSnapshot=" + totalDeltaBytesSinceSnapshot);
      // To reconstruct the object's snapshot S at the current version, we will
      // need to read the most recent snapshot P followed by a sequence of
      // deltas D. To keep the amount of data required for this reconstruction
      // within a constant factor of |S| (the size of S), we write S to disk if
      // k * |S| < |P| + |D|, for some constant k.
      //
      // Computing the snapshot size |S| currently takes linear time, so when
      // appending a sequence of deltas, we only do it once at the end, to avoid
      // taking quadratic time. This is not a problem with small batches of
      // operations from clients, but can make imports time out.
      //
      // TODO(ohler): Provide bound on disk space consumption.
      //
      // TODO(ohler): This formula assumes that reading & reconstructing a
      // snapshot has the same cost per byte as reading & applying a delta.
      // That's probably not true. The cost of applying a delta may not even be
      // linear in the size of that delta (and the same is true for
      // reconstructing from a snapshot); this depends on the model. We should
      // allow for models to influence when to take snapshots, perhaps by
      // letting the model provide a size metric for deltas and snapshots
      // instead of using bytes, or by measuring actual computation time if we
      // can do that reliably. It would be best to make it impossible for
      // models to cause quadratic disk space consumption, though.
      final long k = 2;
      if (k * snapshotBytes < mostRecentSnapshotBytes + totalDeltaBytesSinceSnapshot) {
        log.info("Adding snapshot");
        stagedSnapshotEntries.add(snapshotEntry);
        mostRecentSnapshotBytes = snapshotBytes;
        totalDeltaBytesSinceSnapshot = 0;
        estimatedBytesStaged += snapshotBytes;
      }
    }

    /**
     * A rough estimate of the total bytes currently staged to be written. This may be subject to
     * inaccuracies such as counting Java's UTF-16 characters in strings as one byte each (actual
     * encoding that the API limits are based on is probably UTF-8) and only counting raw payloads
     * without taking metadata, encoding overhead, or indexing overhead into account.
     */
    public long estimatedBytesStaged() {
      return estimatedBytesStaged;
    }

    /**
     * Calls {@code put()} on all staged deltas and snapshots, etc.
     */
    public void finish() throws PermanentFailure, RetryableFailure {
      checkNotFinished();
      finished = true;
      log.info("Flushing " + stagedDeltaEntries.size() + " deltas and "
          + stagedSnapshotEntries.size() + " snapshots");
      put(tx, stagedDeltaEntries, stagedSnapshotEntries);
      tx.runAfterCommit(new Runnable() {
        @Override
        public void run() {
          stateCache.currentStates.put(objectId, new CacheEntry(state.getVersion(), state
              .getState().snapshot(), mostRecentSnapshotBytes, totalDeltaBytesSinceSnapshot));
        }
      });
      stagedDeltaEntries.clear();
      stagedSnapshotEntries.clear();
      estimatedBytesStaged = 0;
    }

    public List<Delta<String>> getStagedDeltas() {
      ImmutableList.Builder<Delta<String>> out = ImmutableList.builder();
      for (DeltaEntry delta : stagedDeltaEntries) {
        out.add(delta.data);
      }
      return out.build();
    }

    public ReadableSlob getStagedState() {
      return state.getState();
    }

    public long getStagedVersion() {
      return state.getVersion();
    }

    public boolean hasNewDeltas() {
      return !stagedDeltaEntries.isEmpty();
    }

    private void checkNotFinished() {
      Preconditions.checkState(!finished, "%s: already finished", this);
    }
  }

  /**
   * Tuple of values returned by {@link #prepareAppender()}.
   */
  public static class AppenderAndCachedDeltas {
    private final Appender appender;
    private final List<Delta<String>> reverseDeltasRead;
    private final DeltaIteratorProvider reverseDeltaIteratorProvider;

    public AppenderAndCachedDeltas(Appender appender, List<Delta<String>> reverseDeltasRead,
        DeltaIteratorProvider reverseDeltaIteratorProvider) {
      Preconditions.checkNotNull(appender, "Null appender");
      Preconditions.checkNotNull(reverseDeltasRead, "Null reverseDeltasRead");
      Preconditions.checkNotNull(reverseDeltaIteratorProvider, "Null reverseDeltaIteratorProvider");
      this.appender = appender;
      this.reverseDeltasRead = reverseDeltasRead;
      this.reverseDeltaIteratorProvider = reverseDeltaIteratorProvider;
    }

    public Appender getAppender() {
      return appender;
    }

    public DeltaIteratorProvider getReverseDeltaIteratorProvider() throws PermanentFailure,
        RetryableFailure {
      return reverseDeltaIteratorProvider;
    }

    public List<Delta<String>> getReverseDeltasRead() {
      return reverseDeltasRead;
    }

    @Override
    public String toString() {
      return "AppenderAndCachedDeltas(" + appender + ", " + reverseDeltasRead + ", "
          + reverseDeltaIteratorProvider + ")";
    }
  }

  public static class DefaultDeltaEntityConverter implements DeltaEntityConverter {
    @Override
    public Delta<String> convert(Entity entity) {
      return new Delta<String>(new SessionId(DatastoreUtil.getExistingProperty(entity,
          DELTA_CLIENT_ID_PROPERTY, String.class)), DatastoreUtil.getExistingProperty(entity,
          DELTA_OP_PROPERTY, Text.class).getValue());
    }
  }

  public interface DeltaEntityConverter {
    Delta<String> convert(Entity entity);
  }

  /**
   * An iterator over a datastore delta result list.
   * 
   * Can be forward or reverse.
   * 
   * The peek methods should be used in conjunction with {@link DeltaIterator#hasNext()} since they
   * will throw {@code NoSuchElementException} if the end of the sequence is reached.
   */
  public class DeltaIterator {
    private final CheckedIterator it;
    private final boolean forward;
    private final long previousResultingVersion;
    @Nullable
    private DeltaEntry peeked = null;

    public DeltaIterator(CheckedIterator it, boolean forward) {
      this.it = Preconditions.checkNotNull(it, "Null it");
      this.forward = forward;
      previousResultingVersion = -1;
    }

    public boolean hasNext() throws PermanentFailure, RetryableFailure {
      return peeked != null || it.hasNext();
    }

    public boolean isForward() {
      return forward;
    }

    public Delta<String> next() throws PermanentFailure, RetryableFailure {
      return nextEntry().data;
    }

    public Delta<String> peek() throws PermanentFailure, RetryableFailure {
      return peekEntry().data;
    }

    @Override
    public String toString() {
      return "DeltaIterator(" + (forward ? "forward" : "reverse") + ", " + previousResultingVersion
          + ")";
    }

    DeltaEntry nextEntry() throws PermanentFailure, RetryableFailure {
      DeltaEntry result = peekEntry();
      peeked = null;
      return result;
    }

    DeltaEntry peekEntry() throws PermanentFailure, RetryableFailure {
      if (peeked == null) {
        // Let it.next() throw if there is no next.
        Entity entity = it.next();
        deltaPropertyMover.postGet(entity);
        peeked = parseDelta(entity);
        checkVersion(peeked);
      }
      return peeked;
    }

    private void checkVersion(DeltaEntry delta) {
      if (previousResultingVersion != -1) {
        long expectedResultingVersion = previousResultingVersion + (forward ? 1 : -1);
        Assert.check(delta.getResultingVersion() == expectedResultingVersion,
            "%s: Expected version %s, got %s", this, expectedResultingVersion, delta
                .getResultingVersion());
      }
    }
  }
  public interface MutationLogFactory {
    // TODO: Rename this method to "get" to avoid connotations of
    // creating objects. Do this any time there's low chance of conflicts.
    MutationLog create(CheckedTransaction tx, ObjectId objectId);
  }
  static interface DeltaIteratorProvider {
    DeltaIterator get() throws PermanentFailure, RetryableFailure;
  }

  // Singleton so that we have a per-process cache.
  @Singleton
  static class StateCache {
    // Key is a pair of root entity kind (= store type) and slob id.
    private final Map<ObjectId, CacheEntry> currentStates;

    @Inject
    StateCache(@SlobLocalCacheExpirationMillis int expirationMillis) {
      // See commit ebb4736368b6d371a1bf5005541d96b88dcac504 for my failed attempt
      // at using CacheBuilder. TODO(ohler): Figure out the right solution to this.
      @SuppressWarnings("deprecation")
      Map<ObjectId, CacheEntry> currentStates =
          new MapMaker().softValues().expireAfterAccess(expirationMillis, TimeUnit.MILLISECONDS)
              .makeMap();
      this.currentStates = currentStates;
    }
  }
  private static class CacheEntry {
    private final long version;
    @Nullable
    private final String snapshot;
    private final long mostRecentSnapshotBytes;
    private final long totalDeltaBytesSinceSnapshot;

    public CacheEntry(long version, @Nullable String snapshot, long mostRecentSnapshotBytes,
        long totalDeltaBytesSinceSnapshot) {
      this.version = version;
      this.snapshot = snapshot;
      this.mostRecentSnapshotBytes = mostRecentSnapshotBytes;
      this.totalDeltaBytesSinceSnapshot = totalDeltaBytesSinceSnapshot;
    }

    public long getMostRecentSnapshotBytes() {
      return mostRecentSnapshotBytes;
    }

    @Nullable
    public String getSnapshot() {
      return snapshot;
    }

    public long getTotalDeltaBytesSinceSnapshot() {
      return totalDeltaBytesSinceSnapshot;
    }

    public long getVersion() {
      return version;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + version + ", " + snapshot + ", "
          + mostRecentSnapshotBytes + ", " + totalDeltaBytesSinceSnapshot + ")";
    }
  }

  private static class DeltaEntry {
    private final ObjectId objectId;
    private final long version;
    private final Delta<String> data;

    DeltaEntry(ObjectId objectId, long version, Delta<String> data) {
      this.objectId = objectId;
      this.version = version;
      this.data = data;
    }

    @Override
    public String toString() {
      return "DeltaEntry(" + objectId + ", " + version + ", " + data + ")";
    }

    long getResultingVersion() {
      return version + 1;
    }
  }

  // Datastore does not allow ids to be 0.

  private static class SnapshotEntry {
    private final ObjectId objectId;
    private final long version;
    private final String snapshot;

    SnapshotEntry(ObjectId objectId, long version, String snapshot) {
      this.objectId = objectId;
      this.version = version;
      this.snapshot = snapshot;
    }

    @Override
    public String toString() {
      return "SnapshotEntry(" + objectId + ", " + version + ", " + snapshot + ")";
    }
  }

  private static final Logger log = Logger.getLogger(MutationLog.class.getName());
  private static final String DELTA_ENTITY_KIND = "Delta";
  private static final String SNAPSHOT_ENTITY_KIND = "Snapshot";
  @VisibleForTesting
  static final String DELTA_OP_PROPERTY = "op";

  @VisibleForTesting
  static final String DELTA_OVERSIZED_OP_PROPERTY = "op__oversized";

  @VisibleForTesting
  static final String DELTA_CLIENT_ID_PROPERTY = "sid";

  @VisibleForTesting
  static final String SNAPSHOT_DATA_PROPERTY = "Data";

  @VisibleForTesting
  static final String SNAPSHOT_OVERSIZED_DATA_PROPERTY = "Data__oversized";

  private static final String METADATA_PROPERTY = "Metadata";

  static Key makeRootEntityKey(ObjectId objectId) {
    Key key = KeyFactory.createKey(objectId.getKind(), objectId.getId());
    return key;
  }

  private static long deltaIdFromVersion(long version) {
    return version + 1;
  }

  private static long estimateSizeBytes(Key key) {
    // It's not documented whether and how the representation returned by
    // keyToString() relates to the representation that the API limits are based
    // on; but in any case, it should be good enough for our estimate.
    long web64Size = KeyFactory.keyToString(key).length();
    // Base-64 strings have 6 bits per character, thus the ratio is 6/8 or 3/4.
    // Divide by 4 first to avoid overflow. The base-64 string should be padded
    // to a length that is a multiple of 4, so there is no rounding error. (If
    // it's not padded, our estimate will be off; but that is tolerable, too.)
    return (web64Size / 4) * 3;
  }

  private static DeltaIteratorProvider makeProvider(final DeltaIterator x) {
    checkNotNull(x, "Null x");
    return new DeltaIteratorProvider() {
      @Override
      public DeltaIterator get() {
        return x;
      }
    };
  }

  private static SnapshotEntry parseSnapshot(Entity e) {
    Key parentKey = e.getKey().getParent();
    ObjectId id = new ObjectId(parentKey.getKind(), parentKey.getName());
    long version = e.getKey().getId();
    return new SnapshotEntry(id, version, DatastoreUtil.getExistingProperty(e,
        SNAPSHOT_DATA_PROPERTY, Text.class).getValue());
  }

  private static void populateDeltaEntity(DeltaEntry in, Entity out) {
    DatastoreUtil.setNonNullUnindexedProperty(out, DELTA_CLIENT_ID_PROPERTY, in.data.getSessionId()
        .getId());
    DatastoreUtil.setNonNullUnindexedProperty(out, DELTA_OP_PROPERTY,
        new Text(in.data.getPayload()));
  }

  private static void populateSnapshotEntity(SnapshotEntry in, Entity out) {
    DatastoreUtil.setNonNullUnindexedProperty(out, SNAPSHOT_DATA_PROPERTY, new Text(in.snapshot));
  }

  private static long versionFromDeltaId(long id) {
    return id - 1;
  }

  private final DeltaEntityConverter deltaEntityConverter;
  private final CheckedTransaction tx;
  private final ObjectId objectId;
  private final SlobModel model;
  private final StateCache stateCache;
  private final OversizedPropertyMover deltaPropertyMover;
  private final OversizedPropertyMover snapshotPropertyMover;

  @AssistedInject
  public MutationLog(CheckedDatastore datastore, DeltaEntityConverter deltaEntityConverter,
      @Assisted CheckedTransaction tx, @Assisted ObjectId objectId, SlobModel model,
      StateCache stateCache,
      OversizedPropertyMover.BlobWriteListener oversizedPropertyBlobWriteListener) {
    this.deltaEntityConverter = deltaEntityConverter;
    this.tx = Preconditions.checkNotNull(tx, "Null tx");
    this.objectId = Preconditions.checkNotNull(objectId, "Null objectId");
    this.model = Preconditions.checkNotNull(model, "Null model");
    this.stateCache = stateCache;
    snapshotPropertyMover =
        new OversizedPropertyMover(datastore, ImmutableList.of(new MovableProperty(
            SNAPSHOT_DATA_PROPERTY, SNAPSHOT_OVERSIZED_DATA_PROPERTY,
            MovableProperty.PropertyType.TEXT)), oversizedPropertyBlobWriteListener);
    deltaPropertyMover =
        new OversizedPropertyMover(datastore, ImmutableList.of(new MovableProperty(
            DELTA_OP_PROPERTY, DELTA_OVERSIZED_OP_PROPERTY, MovableProperty.PropertyType.TEXT)),
            oversizedPropertyBlobWriteListener);
  }

  /** @see #forwardHistory(long, Long, FetchOptions) */
  public DeltaIterator forwardHistory(long minVersion, @Nullable Long maxVersion)
      throws PermanentFailure, RetryableFailure {
    return forwardHistory(minVersion, maxVersion, FetchOptions.Builder.withDefaults());
  }

  /**
   * Returns an iterator over the specified version range of the mutation log, in a forwards
   * direction.
   * 
   * @param maxVersion null to end with the final delta in the mutation log.
   */
  public DeltaIterator forwardHistory(long minVersion, @Nullable Long maxVersion,
      FetchOptions fetchOptions) throws PermanentFailure, RetryableFailure {
    return getDeltaIterator(minVersion, maxVersion, fetchOptions, true);
  }

  // TODO(ohler): eliminate; PreCommitHook should be enough
  @Nullable
  public String getMetadata() throws RetryableFailure, PermanentFailure {
    Key key = makeRootEntityKey(objectId);
    Entity entity = tx.get(key);
    @Nullable
    String result =
        entity == null ? null : DatastoreUtil.getExistingProperty(entity, METADATA_PROPERTY,
            Text.class).getValue();
    log.info("Got " + result);
    return result;
  }

  /**
   * Constructs a model object from the snapshot with the highest version less than or equal to
   * atOrBeforeVersion.
   * 
   * @param atOrBeforeVersion null for current version
   */
  public StateAndVersion getSnapshottedState(@Nullable Long atOrBeforeVersion)
      throws PermanentFailure, RetryableFailure {
    return createObject(getSnapshotEntryAtOrBefore(atOrBeforeVersion));
  }

  /**
   * Returns the current version of the object.
   */
  public long getVersion() throws PermanentFailure, RetryableFailure {
    CheckedIterator deltaKeys =
        getDeltaEntityIterator(0, null, FetchOptions.Builder.withChunkSize(1).limit(1)
            .prefetchSize(1), false, true);
    if (!deltaKeys.hasNext()) {
      return 0;
    }
    return versionFromDeltaId(deltaKeys.next().getKey().getId());
  }

  /**
   * Creates an {@link Appender} for this mutation log and returns it together with some
   * by-products. The by-products can be useful to callers who need data from the datastore that
   * overlaps with what was needed to create the {@code Appender}, to avoid redundant datastore
   * reads.
   */
  public AppenderAndCachedDeltas prepareAppender() throws PermanentFailure, RetryableFailure {
    CacheEntry cached = stateCache.currentStates.get(objectId);
    if (cached != null) {
      long cachedVersion = cached.getVersion();
      // We need to check if a delta with version cachedVersion is present; that
      // would indicate that our cache is out of date. Since we're paranoid, we
      // additionally check that cachedVersion-1 is present (it always has to
      // be).
      //
      // After writing the code to use a key-only query here, I found
      // http://code.google.com/appengine/docs/billing.html#Billable_Resource_Unit_Cost
      // which implies that the cost of this is
      //
      // 1 "Read" + 1 "Small" + (no transform needed ? 0 : 1 "Read" + # reverse
      // deltas needed * 1 "Read")
      //
      // while the cost of using a reverse delta iterator (not key-only, so that
      // we can reuse it and pass it into AppenderAndCachedDeltas below) and
      // always reading the first delta entity would be
      //
      // 2 "Read" + (no transform needed ? 0 : (# reverse deltas needed - 1) * 1 "Read")
      //
      // where a "Read" has a cost of 7 units, a "Small" has a cost of 1 unit.
      //
      // Essentially, the variant implemented here saves 6 units when no deltas
      // are needed for transform, but pays an extra 8 otherwise.
      //
      // When cached != null but another writer interfered, we also pay an extra
      // 8 units compared to sharing the same iterator.
      //
      // It's not clear which of these situation is going to be common and which
      // is not, and whether the cost is worth worrying aboung. I happened to
      // implement it this way first and only found that billing page later, so
      // I'll leave it for now, even though the code is very slighly more
      // complicated. If we ever introduce a delta cache, that would make the
      // case of having no deltas to read for transform more common, and would
      // (presumably) make sharing the iterator harder, so this code would be a
      // better starting point for that.
      boolean cacheValid;
      if (cachedVersion == 0) {
        cacheValid = getDeltaEntity(0L) == null;
      } else {
        CheckedIterator deltaKeys =
            getDeltaEntityIterator(cachedVersion - 1, cachedVersion + 1, FetchOptions.Builder
                .withChunkSize(2).limit(2).prefetchSize(2), true, true);
        if (!deltaKeys.hasNext()) {
          throw new RuntimeException("Missing data: Delta " + cachedVersion + " not found: "
              + deltaKeys);
        }
        deltaKeys.next();
        cacheValid = !deltaKeys.hasNext();
      }
      if (cacheValid) {
        log.info("MutationLog cache: Constructing appender based on cached slob version "
            + cachedVersion);
        return new AppenderAndCachedDeltas(new Appender(createObject(cached.getVersion(), cached
            .getSnapshot()), cached.getMostRecentSnapshotBytes(), cached
            .getTotalDeltaBytesSinceSnapshot()), ImmutableList.<Delta<String>> of(),
            new DeltaIteratorProvider() {
              DeltaIterator i = null;

              @Override
              public DeltaIterator get() throws PermanentFailure, RetryableFailure {
                if (i == null) {
                  i = getDeltaIterator(0, null, FetchOptions.Builder.withDefaults(), false);
                }
                return i;
              }
            });
      } else {
        log.info("MutationLog cache: Another writer interfered (cached slob version was "
            + cachedVersion + ")");
        stateCache.currentStates.remove(objectId);
      }
    } else {
      log.info("MutationLog cache: No slob version cached");
    }
    return prepareAppenderSlowCase();
  }

  // TODO(ohler): eliminate; PreCommitHook should be enough
  public void putMetadata(String metadata) throws RetryableFailure, PermanentFailure {
    Entity e = new Entity(makeRootEntityKey(objectId));
    DatastoreUtil.setNonNullUnindexedProperty(e, METADATA_PROPERTY, new Text(metadata));
    log.info("Writing metadata: " + metadata);
    tx.put(e);
  }

  /**
   * Reconstructs the object at the specified version (current version if null).
   */
  public StateAndVersion reconstruct(@Nullable Long atVersion) throws PermanentFailure,
      RetryableFailure {
    checkRange(atVersion, null);

    StateAndVersion state = getSnapshottedState(atVersion);
    long startVersion = state.getVersion();
    Assert.check(atVersion == null || startVersion <= atVersion);

    DeltaIterator it =
        forwardHistory(startVersion, atVersion, FetchOptions.Builder
            .withPrefetchSize(atVersion == null ?
            // We have to fetch everything; hopefully it will fit in
            // a single RPC / in memory.
                9999
                // Fetch what we need, all at once. Again, hope it fits.
                : Ints.checkedCast(atVersion - startVersion)));
    while (it.hasNext()) {
      Delta<String> delta = it.next();
      try {
        state.apply(delta);
      } catch (DeltaRejected e) {
        throw new PermanentFailure("Corrupt snapshot or delta history " + objectId + " @"
            + state.getVersion(), e);
      }
    }
    if (atVersion != null && state.getVersion() < atVersion) {
      throw new RuntimeException("Object max version is " + state.getVersion() + ", requested "
          + atVersion);
    }
    log.info("Reconstructed requested version " + atVersion + " from snapshot at " + startVersion
        + " followed by " + (state.getVersion() - startVersion) + " deltas");
    return state;
  }

  /** @see #reverseHistory(long, Long, FetchOptions) */
  public DeltaIterator reverseHistory(long minVersion, @Nullable Long maxVersion)
      throws PermanentFailure, RetryableFailure {
    return reverseHistory(minVersion, maxVersion, FetchOptions.Builder.withDefaults());
  }

  /**
   * Returns an iterator over the specified version range of the mutation log, in a backwards
   * direction.
   * 
   * @param maxVersion null to begin with the final delta in the mutation log.
   */
  public DeltaIterator reverseHistory(long minVersion, @Nullable Long maxVersion,
      FetchOptions fetchOptions) throws PermanentFailure, RetryableFailure {
    return getDeltaIterator(minVersion, maxVersion, fetchOptions, false);
  }

  private void checkDeltaDoesNotExist(long version) throws RetryableFailure, PermanentFailure {
    // This check is not necessary but let's be paranoid.
    // TODO(danilatos): Make this async and check the result on flush() to
    // improve latency. Or, make an informed decision to remove it.
    Entity existing = getDeltaEntity(version);
    Assert.check(existing == null, "Datastore fail?  Found unexpected delta: %s, %s, %s", objectId,
        version, existing);
  }

  private void checkRange(@Nullable Long startVersion, @Nullable Long endVersion) {
    if (startVersion == null) {
      Preconditions.checkArgument(endVersion == null,
          "startVersion == null implies endVersion == null, not %s", endVersion);
    } else {
      Preconditions.checkArgument(startVersion >= 0
          && (endVersion == null || startVersion <= endVersion),
          "Invalid range requested (%s to %s)", startVersion, endVersion);

      // I doubt this would really happen, but...
      Assert.check(endVersion == null || (endVersion - startVersion <= Integer.MAX_VALUE),
          "Range too large: %s to %s", startVersion, endVersion);
    }
  }

  private StateAndVersion createObject(long version, @Nullable String snapshot) {
    try {
      return new StateAndVersion(model.create(snapshot), version);
    } catch (InvalidSnapshot e) {
      throw new RuntimeException("Could not create model from snapshot at version " + version
          + ": " + snapshot, e);
    }
  }

  private StateAndVersion createObject(@Nullable SnapshotEntry entry) {
    if (entry == null) {
      return createObject(0, null);
    } else {
      return createObject(entry.version, entry.snapshot);
    }
  }

  private long estimateSizeBytes(DeltaEntry deltaEntry) {
    return estimateSizeBytes(makeDeltaKey(deltaEntry)) + DELTA_CLIENT_ID_PROPERTY.length()
        + deltaEntry.data.getSessionId().getId().length() + DELTA_OP_PROPERTY.length()
        + deltaEntry.data.getPayload().length();
  }

  private long estimateSizeBytes(SnapshotEntry snapshotEntry) {
    return estimateSizeBytes(makeSnapshotKey(snapshotEntry)) + SNAPSHOT_DATA_PROPERTY.length()
        + snapshotEntry.snapshot.length();
  }

  @Nullable
  private Entity getDeltaEntity(long version) throws RetryableFailure, PermanentFailure {
    return tx.get(makeDeltaKey(objectId, version));
  }

  private CheckedIterator getDeltaEntityIterator(long startVersion, @Nullable Long endVersion,
      FetchOptions fetchOptions, boolean forward, boolean keysOnly) throws PermanentFailure,
      RetryableFailure {
    checkRange(startVersion, endVersion);
    if (endVersion != null && startVersion == endVersion) {
      return CheckedIterator.EMPTY;
    }
    Query.Filter filter =
        FilterOperator.GREATER_THAN_OR_EQUAL.of(Entity.KEY_RESERVED_PROPERTY, makeDeltaKey(
            objectId, startVersion));
    if (endVersion != null) {
      filter =
          Query.CompositeFilterOperator.and(filter, FilterOperator.LESS_THAN.of(
              Entity.KEY_RESERVED_PROPERTY, makeDeltaKey(objectId, endVersion)));
    }
    Query q =
        new Query(objectId.getKind() + DELTA_ENTITY_KIND).setAncestor(makeRootEntityKey(objectId))
            .setFilter(filter).addSort(Entity.KEY_RESERVED_PROPERTY,
                forward ? SortDirection.ASCENDING : SortDirection.DESCENDING);
    if (keysOnly) {
      q.setKeysOnly();
    }
    return tx.prepare(q).asIterator(fetchOptions);
  }

  private DeltaIterator getDeltaIterator(long startVersion, @Nullable Long endVersion,
      FetchOptions fetchOptions, boolean forward) throws PermanentFailure, RetryableFailure {
    return new DeltaIterator(getDeltaEntityIterator(startVersion, endVersion, fetchOptions,
        forward, false), forward);
  }

  @Nullable
  private SnapshotEntry getSnapshotEntryAtOrBefore(@Nullable Long atOrBeforeVersion)
      throws RetryableFailure, PermanentFailure {
    Query q =
        new Query(objectId.getKind() + SNAPSHOT_ENTITY_KIND).setAncestor(
            makeRootEntityKey(objectId)).addSort(Entity.KEY_RESERVED_PROPERTY,
            SortDirection.DESCENDING);
    if (atOrBeforeVersion != null) {
      q.setFilter(FilterOperator.LESS_THAN_OR_EQUAL.of(Entity.KEY_RESERVED_PROPERTY,
          makeSnapshotKey(objectId, atOrBeforeVersion)));
    }
    Entity e = tx.prepare(q).getFirstResult();
    log.info("query " + q + " returned first result " + e);
    if (e == null) {
      return null;
    } else {
      snapshotPropertyMover.postGet(e);
      return parseSnapshot(e);
    }
  }

  private Key makeDeltaKey(DeltaEntry e) {
    return makeDeltaKey(e.objectId, e.version);
  }

  private Key makeDeltaKey(ObjectId objectId, long version) {
    return KeyFactory.createKey(makeRootEntityKey(objectId),
        objectId.getKind() + DELTA_ENTITY_KIND, deltaIdFromVersion(version));
  }

  private Key makeSnapshotKey(ObjectId objectId, long version) {
    return KeyFactory.createKey(makeRootEntityKey(objectId), objectId.getKind()
        + SNAPSHOT_ENTITY_KIND, version);
  }

  private Key makeSnapshotKey(SnapshotEntry e) {
    return makeSnapshotKey(e.objectId, e.version);
  }

  private DeltaEntry parseDelta(Entity entity) {
    Key parentKey = entity.getKey().getParent();
    ObjectId slobId = new ObjectId(parentKey.getKind(), parentKey.getName());
    long version = versionFromDeltaId(entity.getKey().getId());
    return new DeltaEntry(slobId, version, deltaEntityConverter.convert(entity));
  }

  private AppenderAndCachedDeltas prepareAppenderSlowCase() throws PermanentFailure,
      RetryableFailure {
    DeltaIterator deltaIterator =
        getDeltaIterator(0, null, FetchOptions.Builder.withDefaults(), false);
    if (!deltaIterator.hasNext()) {
      log.info("Prepared appender at version 0");
      checkDeltaDoesNotExist(0);
      return new AppenderAndCachedDeltas(new Appender(createObject(null), 0, 0), ImmutableList
          .<Delta<String>> of(), makeProvider(deltaIterator));
    } else {
      SnapshotEntry snapshotEntry = getSnapshotEntryAtOrBefore(null);
      StateAndVersion state = createObject(snapshotEntry);
      long snapshotVersion = state.getVersion();
      long snapshotBytes = snapshotEntry == null ? 0 : estimateSizeBytes(snapshotEntry);

      // Read deltas between snapshot and current version. Since we determine
      // the current version by reading the first delta (in our reverse
      // iterator), we always read at least one delta even if none are needed to
      // reconstruct the current version.
      DeltaEntry finalDelta = deltaIterator.nextEntry();
      long currentVersion = finalDelta.getResultingVersion();
      if (currentVersion == snapshotVersion) {
        // We read a delta but it precedes the snapshot. It still has to go
        // into deltasRead in our AppenderAndCachedDeltas to ensure that there
        // is no gap between deltasRead and reverseIterator.
        log.info("Prepared appender; snapshotVersion=currentVersion=" + currentVersion);
        checkDeltaDoesNotExist(snapshotVersion);
        return new AppenderAndCachedDeltas(new Appender(state, snapshotBytes, 0), ImmutableList
            .of(finalDelta.data), makeProvider(deltaIterator));
      } else {
        // We need to apply the delta and perhaps others. Collect them.
        ImmutableList.Builder<Delta<String>> deltaAccu = ImmutableList.builder();
        deltaAccu.add(finalDelta.data);
        long totalDeltaBytesSinceSnapshot = estimateSizeBytes(finalDelta);
        {
          DeltaEntry delta = finalDelta;
          while (delta.version != snapshotVersion) {
            delta = deltaIterator.nextEntry();
            deltaAccu.add(delta.data);
            totalDeltaBytesSinceSnapshot += estimateSizeBytes(delta);
          }
        }
        ImmutableList<Delta<String>> reverseDeltas = deltaAccu.build();
        // Now iterate forward and apply the deltas.
        for (Delta<String> delta : Lists.reverse(reverseDeltas)) {
          try {
            state.apply(delta);
          } catch (DeltaRejected e) {
            throw new RuntimeException("Corrupt snapshot or delta history: " + objectId
                + " rejected delta " + delta + ": " + state);
          }
        }
        log.info("Prepared appender; snapshotVersion=" + snapshotVersion + ", "
            + reverseDeltas.size() + " deltas");
        checkDeltaDoesNotExist(state.getVersion());
        return new AppenderAndCachedDeltas(new Appender(state, snapshotBytes,
            totalDeltaBytesSinceSnapshot), reverseDeltas, makeProvider(deltaIterator));
      }
    }
  }

  private void put(CheckedTransaction tx, List<DeltaEntry> newDeltas,
      List<SnapshotEntry> newSnapshots) throws PermanentFailure, RetryableFailure {
    Preconditions.checkNotNull(newDeltas, "null newEntries");
    Preconditions.checkNotNull(newSnapshots, "null newSnapshots");
    List<Entity> deltaEntities = Lists.newArrayListWithCapacity(newDeltas.size());
    for (DeltaEntry entry : newDeltas) {
      Key key = makeDeltaKey(entry);
      Entity newEntity = new Entity(key);
      populateDeltaEntity(entry, newEntity);
      parseDelta(newEntity); // Verify it parses with no exceptions.
      deltaEntities.add(newEntity);
    }
    List<Entity> snapshotEntities = Lists.newArrayListWithCapacity(newSnapshots.size());
    for (SnapshotEntry entry : newSnapshots) {
      Key key = makeSnapshotKey(entry);
      Entity newEntity = new Entity(key);
      populateSnapshotEntity(entry, newEntity);
      parseSnapshot(newEntity); // Verify it parses with no exceptions.
      snapshotEntities.add(newEntity);
    }
    for (Entity entity : deltaEntities) {
      deltaPropertyMover.prePut(entity);
    }
    for (Entity entity : snapshotEntities) {
      snapshotPropertyMover.prePut(entity);
    }
    tx.put(ConcatenatingList.of(snapshotEntities, deltaEntities));
  }

}
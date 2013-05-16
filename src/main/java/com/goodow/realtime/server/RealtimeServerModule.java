/*
 * Copyright 2012 Goodow.com
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
package com.goodow.realtime.server;

import com.goodow.realtime.server.model.ObjectId;
import com.goodow.realtime.server.model.RealtimeSlobAdapter;

import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Named;
import com.google.walkaround.slob.server.AccessChecker;
import com.google.walkaround.slob.server.AffinityMutationProcessor.StoreBackendInstanceCount;
import com.google.walkaround.slob.server.AffinityMutationProcessor.StoreBackendName;
import com.google.walkaround.slob.server.MutationLog;
import com.google.walkaround.slob.server.MutationLog.DefaultDeltaEntityConverter;
import com.google.walkaround.slob.server.MutationLog.MutationLogFactory;
import com.google.walkaround.slob.server.PostCommitAction;
import com.google.walkaround.slob.server.PostCommitActionIntervalMillis;
import com.google.walkaround.slob.server.PostCommitTaskUrl;
import com.google.walkaround.slob.server.PreCommitAction;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.server.SlobFacilitiesImpl;
import com.google.walkaround.slob.server.SlobLocalCacheExpirationMillis;
import com.google.walkaround.slob.server.SlobMessageRouter.SlobChannelExpirationSeconds;
import com.google.walkaround.slob.server.SlobStore;
import com.google.walkaround.slob.server.SlobStoreImpl;
import com.google.walkaround.slob.shared.SlobModel;
import com.google.walkaround.util.server.MonitoringVars;
import com.google.walkaround.util.server.RetryHelper;
import com.google.walkaround.util.server.RetryHelper.PermanentFailure;
import com.google.walkaround.util.server.RetryHelper.RetryableFailure;
import com.google.walkaround.util.server.Util;
import com.google.walkaround.util.server.appengine.CheckedDatastore;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedTransaction;
import com.google.walkaround.util.server.appengine.DatastoreUtil;
import com.google.walkaround.util.server.appengine.MemcacheDeletionQueue;
import com.google.walkaround.util.server.appengine.MemcacheTable;
import com.google.walkaround.util.server.appengine.OversizedPropertyMover;
import com.google.walkaround.util.server.auth.DigestUtils2.Secret;
import com.google.walkaround.util.server.flags.FlagDeclaration;
import com.google.walkaround.util.server.flags.FlagFormatException;
import com.google.walkaround.util.server.flags.JsonFlags;
import com.google.walkaround.wave.server.FlagConfiguration;

import java.lang.annotation.Annotation;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

public class RealtimeServerModule extends AbstractModule {

  private static final Logger log = Logger.getLogger(RealtimeServerModule.class.getName());
  private static final String SECRET_ENTITY_KIND = "Secret";
  private static final com.google.appengine.api.datastore.Key SECRET_KEY = KeyFactory.createKey(
      SECRET_ENTITY_KIND, "secret");
  private static final String SECRET_PROPERTY = "secret";

  private static <F, P> Module factoryModule(Class<F> factoryClass, Class<P> productClass) {
    return new FactoryModuleBuilder().implement(productClass, productClass).build(factoryClass);
  }

  @Override
  protected void configure() {
    // How heavyweight is SecureRandom? Until we know, let's use only one.
    //
    // This seems preferable over the .toInstance() below, but it doesn't work.
    // bind(SecureRandom.class).in(Singleton.class);
    final SecureRandom random = new SecureRandom();
    bind(SecureRandom.class).toInstance(random);
    bind(Random.class).to(SecureRandom.class);

    bind(MemcacheTable.Factory.class).to(MemcacheTable.FactoryImpl.class);

    bind(Key.get(Queue.class, MemcacheDeletionQueue.class)).toInstance(
        QueueFactory.getQueue("memcache-deletion"));

    bind(String.class).annotatedWith(PostCommitTaskUrl.class).toInstance(
        RealtimeServletModule.POST_COMMIT_TASK_PATH);

    bind(MutationLog.DeltaEntityConverter.class).to(DefaultDeltaEntityConverter.class);

    bind(OversizedPropertyMover.BlobWriteListener.class).toInstance(
        OversizedPropertyMover.NULL_LISTENER);

    JsonFlags.bind(binder(), Arrays.asList(FlagName.values()), binder().getProvider(
        Key.get(new TypeLiteral<Map<FlagDeclaration, Object>>() {
        }, FlagConfiguration.class)));

    bindToFlag(String.class, StoreBackendName.class, FlagName.STORE_SERVER);
    bindToFlag(Integer.class, StoreBackendInstanceCount.class, FlagName.NUM_STORE_SERVERS);
    bindToFlag(Integer.class, SlobChannelExpirationSeconds.class,
        FlagName.OBJECT_CHANNEL_EXPIRATION_SECONDS);
    bindToFlag(Integer.class, PostCommitActionIntervalMillis.class,
        FlagName.POST_COMMIT_ACTION_INTERVAL_MILLIS);
    bindToFlag(Integer.class, SlobLocalCacheExpirationMillis.class,
        FlagName.SLOB_LOCAL_CACHE_EXPIRATION_MILLIS);

    bind(MonitoringVars.class).toInstance(MonitoringVars.NULL_IMPL);

    bind(SlobFacilities.class).to(SlobFacilitiesImpl.class);
    bind(SlobStore.class).to(SlobStoreImpl.class);
    install(factoryModule(MutationLogFactory.class, MutationLog.class));

    // Make sure a binding for the Set exists.
    Multibinder.newSetBinder(binder(), PreCommitAction.class);
    // Make sure a binding for the Set exists.
    Multibinder.newSetBinder(binder(), PostCommitAction.class);

    bind(SlobModel.class).to(RealtimeSlobAdapter.class);
    bind(AccessChecker.class).toInstance(new AccessChecker() {
      @Override
      public void checkCanCreate(ObjectId objectId) {
      }

      @Override
      public void checkCanModify(ObjectId objectId) {
      }

      @Override
      public void checkCanRead(ObjectId objectId) {
      }
    });
  }

  @Provides
  @FlagConfiguration
  @Singleton
  Map<FlagDeclaration, Object> provideFlagConfiguration(@Named("raw flag data") String rawFlagData)
      throws FlagFormatException {
    return JsonFlags.parse(Arrays.asList(FlagName.values()), rawFlagData);
  }

  @Provides
  @Named("raw flag data")
  @Singleton
  String provideRawFlagData(@Named("webinf root") String webinfRoot) {
    return Util.slurpRequired(webinfRoot + "/flags.json");
  }

  @Provides
  // The secret is read from the datastore; @Singleton to cache it for
  // efficiency. If an admin deletes the secret entity, a new secret will be
  // generated, but existing instances will continue to use the old one.
  // Re-deploying should fix this since it restarts all instances.
  @Singleton
  Secret provideSecret(final CheckedDatastore datastore, final Random random)
      throws PermanentFailure {
    return new RetryHelper().run(new RetryHelper.Body<Secret>() {
      @Override
      public Secret run() throws RetryableFailure, PermanentFailure {
        CheckedTransaction tx = datastore.beginTransaction();
        try {
          {
            Entity e = tx.get(SECRET_KEY);
            if (e != null) {
              log.info("Using stored secret");
              return Secret.of(DatastoreUtil.getExistingProperty(e, SECRET_PROPERTY, Blob.class)
                  .getBytes());
            }
          }
          Secret newSecret = Secret.generate(random);
          Entity e = new Entity(SECRET_KEY);
          DatastoreUtil.setNonNullUnindexedProperty(e, SECRET_PROPERTY, new Blob(newSecret
              .getBytes()));
          tx.put(e);
          tx.commit();
          log.info("Generated new secret");
          return newSecret;
        } finally {
          tx.close();
        }
      }
    });
  }

  private <T> void bindToFlag(Class<T> type, Class<? extends Annotation> annotation,
      FlagName flagName) {
    bind(Key.get(type, annotation)).toProvider(
        getProvider(Key.get(type, new FlagName.FlagImpl(flagName))));
  }
}
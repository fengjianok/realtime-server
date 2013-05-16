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
package com.google.walkaround.slob.server;

import com.goodow.realtime.server.model.ObjectId;

import com.google.appengine.api.datastore.Key;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.walkaround.slob.server.MutationLog.MutationLogFactory;

public class SlobFacilitiesImpl implements SlobFacilities {
  // These are providers since many users will only need one.
  @Inject
  Provider<SlobStore> slobStore;
  @Inject
  Provider<LocalMutationProcessor> localMutationProcessor;
  @Inject
  Provider<MutationLogFactory> mutationLogFactory;
  @Inject
  Provider<PostCommitActionScheduler> postCommitActionScheduler;

  @Override
  public LocalMutationProcessor getLocalMutationProcessor() {
    return localMutationProcessor.get();
  }

  @Override
  public MutationLogFactory getMutationLogFactory() {
    return mutationLogFactory.get();
  }

  @Override
  public PostCommitActionScheduler getPostCommitActionScheduler() {
    return postCommitActionScheduler.get();
  }

  @Override
  public SlobStore getSlobStore() {
    return slobStore.get();
  }

  @Override
  public Key makeRootEntityKey(ObjectId slobId) {
    return MutationLog.makeRootEntityKey(slobId);
  }
}
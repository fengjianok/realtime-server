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

import com.google.appengine.api.backends.BackendService;
import com.google.appengine.api.backends.BackendServiceFactory;
import com.google.appengine.api.blobstore.BlobInfoFactory;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.channel.ChannelService;
import com.google.appengine.api.channel.ChannelServiceFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceConfig;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.ImplicitTransactionManagementPolicy;
import com.google.appengine.api.datastore.ReadPolicy;
import com.google.appengine.api.images.ImagesService;
import com.google.appengine.api.images.ImagesServiceFactory;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.search.SearchService;
import com.google.appengine.api.search.SearchServiceFactory;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.walkaround.wave.server.DatastoreTimeoutMillis;

public class AppEngineModule extends AbstractModule {

  @Override
  protected void configure() {
  }

  @Provides
  BackendService provideBackendService() {
    return BackendServiceFactory.getBackendService();
  }

  @Provides
  BlobInfoFactory provideBlobInfoFactory() {
    return new BlobInfoFactory();
  }

  @Provides
  BlobstoreService provideBlobstoreService() {
    return BlobstoreServiceFactory.getBlobstoreService();
  }

  @Provides
  ChannelService provideChannelService() {
    return ChannelServiceFactory.getChannelService();
  }

  @Provides
  DatastoreService provideDatastore(@DatastoreTimeoutMillis long datastoreTimeoutMillis) {
    return DatastoreServiceFactory.getDatastoreService(DatastoreServiceConfig.Builder.withDeadline(
        datastoreTimeoutMillis * 1000.0).implicitTransactionManagementPolicy(
        ImplicitTransactionManagementPolicy.NONE).readPolicy(
        new ReadPolicy(ReadPolicy.Consistency.STRONG)));
  }

  @Provides
  ImagesService provideImagesService() {
    return ImagesServiceFactory.getImagesService();
  }

  @Provides
  SearchService provideIndexManager() {
    return SearchServiceFactory.getSearchService();
  }

  @Provides
  MemcacheService provideMemcache() {
    return MemcacheServiceFactory.getMemcacheService();
  }

  @Provides
  URLFetchService provideUrlFetchService() {
    return URLFetchServiceFactory.getURLFetchService();
  }

  @Provides
  UserService provideUserService() {
    return UserServiceFactory.getUserService();
  }
}
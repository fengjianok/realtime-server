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

import com.goodow.realtime.channel.rpc.Constants;
import com.goodow.realtime.server.rpc.DeltaHandler;
import com.goodow.realtime.server.rpc.PollHandler;
import com.goodow.realtime.server.rpc.RevisionHandler;
import com.goodow.realtime.server.rpc.SaveHandler;
import com.goodow.realtime.server.rpc.SnapshotHandler;
import com.goodow.realtime.server.servlet.PostCommitTaskHandler;
import com.goodow.realtime.server.servlet.StoreMutateHandler;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.tools.appstats.AppstatsFilter;
import com.google.appengine.tools.appstats.AppstatsServlet;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Provides;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.servlet.RequestScoped;
import com.google.inject.servlet.ServletModule;
import com.google.walkaround.slob.server.AffinityMutationProcessor;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.ExactPathHandlers;
import com.google.walkaround.util.server.servlet.HandlerServlet;
import com.google.walkaround.util.server.servlet.PrefixPathHandlers;
import com.google.walkaround.util.server.servlet.RedirectServlet;
import com.google.walkaround.util.server.servlet.RequestStatsFilter;

import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.Filter;

public class RealtimeServletModule extends ServletModule {

  private static final Logger log = Logger.getLogger(RealtimeServletModule.class.getName());

  static final String POST_COMMIT_TASK_PATH = "/taskqueue/postcommit";

  /** Path bindings for handlers that serve exact paths only. */
  private static final ImmutableMap<String, Class<? extends AbstractHandler>> EXACT_PATH_HANDLERS =
      new ImmutableMap.Builder<String, Class<? extends AbstractHandler>>()
      // Endpoints for RPCs etc.
          .put("/" + Constants.Services.SNAPSHOT, SnapshotHandler.class).put(
              "/" + Constants.Services.DELTA, DeltaHandler.class).put(
              "/" + Constants.Services.REVISION, RevisionHandler.class).put(
              "/" + Constants.Services.POLL, PollHandler.class).put("/" + Constants.Services.SAVE,
              SaveHandler.class)

          // Backend servers. Could potentially use a separate Guice module.
          .put("/" + AffinityMutationProcessor.PATH, StoreMutateHandler.class)

          // Task queue stuff.
          .put(POST_COMMIT_TASK_PATH, PostCommitTaskHandler.class)

          .build();

  /** Path bindings for handlers that serve all paths under some prefix. */
  private static final ImmutableMap<String, Class<? extends AbstractHandler>> PREFIX_PATH_HANDLERS =
      new ImmutableMap.Builder<String, Class<? extends AbstractHandler>>().build();

  /** Checks that there are no conflicts between paths in the handler maps. */
  private static void validatePaths() {
    for (String prefix : PREFIX_PATH_HANDLERS.keySet()) {
      for (String exact : EXACT_PATH_HANDLERS.keySet()) {
        if (exact.startsWith(prefix)) {
          throw new AssertionError("Handler conflict between prefix path " + prefix
              + " and exact path " + exact);
        }
      }
      for (String otherPrefix : PREFIX_PATH_HANDLERS.keySet()) {
        if (!otherPrefix.equals(prefix) && otherPrefix.startsWith(prefix)) {
          throw new AssertionError("Handler conflict between prefix path " + prefix
              + " and prefix path " + otherPrefix);
        }
      }
    }
  }

  private final Iterable<? extends Filter> extraFilters;

  public RealtimeServletModule(Iterable<? extends Filter> extraFilters) {
    this.extraFilters = extraFilters;
  }

  @Override
  protected void configureServlets() {
    // NOTE: Appstats only returns memory once the request completes and can lead to OOMs
    // for very long-running requests.
    if (SystemProperty.environment.value() != SystemProperty.Environment.Value.Development) {
      // Doesn't work in local dev server mode.
      filter("*").through(AppstatsFilter.class, ImmutableMap.of("basePath", "/admin/appstats/"));
    }
    // We want appstats and ServerExceptionFilter as the outermost layers. We
    // use the extraFilters hook for monitoring, so it has to be before
    // RequestStatsFilter; that means it has to be here. Other uses might need
    // additional hooks to put filters elsewhere (e.g. after authentication).
    for (Filter f : extraFilters) {
      filter("*").through(f);
    }
    filter("*").through(RequestStatsFilter.class);

    serve("/admin/").with(new RedirectServlet("/admin"));

    // All of the exact paths in EXACT_PATH_HANDLERS, and all the path prefixes
    // from PREFIX_PATH_HANDLERS, are served with HandlerServlet.
    validatePaths();
    {
      MapBinder<String, AbstractHandler> exactPathBinder =
          MapBinder.newMapBinder(binder(), String.class, AbstractHandler.class,
              ExactPathHandlers.class);
      for (Map.Entry<String, Class<? extends AbstractHandler>> e : EXACT_PATH_HANDLERS.entrySet()) {
        serve(e.getKey()).with(HandlerServlet.class);
        exactPathBinder.addBinding(e.getKey()).to(e.getValue());
      }
    }
    {
      MapBinder<String, AbstractHandler> prefixPathBinder =
          MapBinder.newMapBinder(binder(), String.class, AbstractHandler.class,
              PrefixPathHandlers.class);
      for (Map.Entry<String, Class<? extends AbstractHandler>> e : PREFIX_PATH_HANDLERS.entrySet()) {
        serve(e.getKey() + "/*").with(HandlerServlet.class);
        prefixPathBinder.addBinding(e.getKey()).to(e.getValue());
      }
    }

    bind(AppstatsFilter.class).in(Singleton.class);
    bind(AppstatsServlet.class).in(Singleton.class);
    serve("/admin/appstats*").with(AppstatsServlet.class);

    // for (String path : Arrays.asList("/connect", "/submitdelta", "/channel", "/history",
    // "/contacts", "/photos")) {
    // filter(path).through(RpcAuthFilter.class);
    // }
  }

  // RequestScoped because we don't know how efficient it is, so we want it cached.
  @Provides
  @RequestScoped
  User provideAppengineUser(UserService userService) {
    User user = userService.getCurrentUser();
    if (user == null) {
      throw new ProvisionException("Not logged in");
    }
    return user;
  }
}
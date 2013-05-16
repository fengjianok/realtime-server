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
package com.goodow.realtime.server.rpc;

import com.goodow.realtime.channel.rpc.Constants;
import com.goodow.realtime.channel.rpc.Constants.Params;
import com.goodow.realtime.id.IdGenerator;
import com.goodow.realtime.server.model.ObjectId;
import com.goodow.realtime.server.model.RealtimeLoader;
import com.goodow.realtime.server.model.SessionId;
import com.goodow.realtime.util.Pair;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.walkaround.slob.server.AccessDeniedException;
import com.google.walkaround.slob.server.SlobNotFoundException;
import com.google.walkaround.slob.server.SlobStore.ConnectResult;
import com.google.walkaround.util.server.servlet.AbstractHandler;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SnapshotHandler extends AbstractHandler {
  private static final Logger log = Logger.getLogger(SnapshotHandler.class.getName());

  @Inject
  private RealtimeLoader loader;
  @Inject
  private IdGenerator idGenerator;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String id = requireParameter(req, Constants.Params.ID);
    String revisionString = optionalParameter(req, Constants.Params.REVISION, null);
    @Nullable
    Long revision = revisionString == null ? null : Long.parseLong(revisionString);

    JsonObject obj;
    try {
      if (revision == null) {
        String sid = idGenerator.next(15);
        Pair<ConnectResult, String> pair = loader.load(id, new SessionId(sid), true);
        obj = new JsonObject();
        obj.addProperty(Params.SESSION_ID, sid);
        serialize(pair, obj);
      } else {
        String snapshot = loader.loadStaticAtVersion(new ObjectId(id), revision);
        obj = new JsonObject();
        obj.add(Constants.Params.SNAPSHOT, new JsonParser().parse(snapshot));
      }
    } catch (AccessDeniedException e) {
      log.log(Level.SEVERE, "Object not found or access denied", e);
      return;
    } catch (SlobNotFoundException e) {
      log.log(Level.SEVERE, "Object not found or access denied", e);
      return;
    } catch (IOException e) {
      log.log(Level.SEVERE, "Server error loading object", e);
      return;
    }

    resp.setContentType("application/json");
    Util.writeJsonResult(resp.getWriter(), obj.toString());
  }

  private final JsonObject serialize(Pair<ConnectResult, String> pair, JsonObject obj) {
    // obj.addProperty(Constants.Params.TOKEN, pair.first.getChannelToken());
    obj.addProperty(Constants.Params.REVISION, pair.first.getVersion());
    obj.add(Constants.Params.SNAPSHOT, new JsonParser().parse(pair.second));
    return obj;
  }
}
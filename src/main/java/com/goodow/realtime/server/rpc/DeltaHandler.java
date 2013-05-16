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

import com.goodow.realtime.channel.rpc.Constants.Params;
import com.goodow.realtime.server.model.Delta;
import com.goodow.realtime.server.model.DeltaSerializer;
import com.goodow.realtime.server.model.ObjectId;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.walkaround.slob.server.AccessDeniedException;
import com.google.walkaround.slob.server.LocalMutationProcessor;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.server.SlobNotFoundException;
import com.google.walkaround.slob.server.SlobStore;
import com.google.walkaround.slob.server.SlobStore.HistoryResult;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.BadRequestException;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Used by the client to notify the server of connection to an object at a specific revision, or to
 * refresh a channel.
 */
public class DeltaHandler extends AbstractHandler {

  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(DeltaHandler.class.getName());

  @Inject
  SlobFacilities slobFacilities;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String id = optionalParameter(req, Params.ID, null);
    JsonObject toRtn;
    try {
      if (id != null) {
        Long startRev = Long.parseLong(requireParameter(req, Params.START_REVISION));
        String endRevString = optionalParameter(req, Params.END_REVISION, null);
        Long endRev = endRevString == null ? null : Long.parseLong(endRevString);
        toRtn = fetchDeltas(new ObjectId(id), startRev - 1, endRev);
      } else {
        JsonElement keys = new JsonParser().parse(req.getReader());
        assert keys.isJsonArray();
        // String sid = requireParameter(req, Params.SESSION_ID);
        toRtn = fetchHistories(keys.getAsJsonArray());
      }
    } catch (SlobNotFoundException e) {
      throw new BadRequestException("Object not found or access denied", e);
    } catch (AccessDeniedException e) {
      throw new BadRequestException("Object not found or access denied", e);
    } catch (NumberFormatException nfe) {
      throw new BadRequestException("Parse error", nfe);
    }

    resp.setContentType("application/json");
    Util.writeJsonResult(resp.getWriter(), toRtn.toString());
  }

  private JsonObject fetchDeltas(ObjectId key, long version, Long endVersion) throws IOException,
      SlobNotFoundException, AccessDeniedException {
    SlobStore store = slobFacilities.getSlobStore();
    JsonObject result = new JsonObject();
    HistoryResult history = store.loadHistory(key, version, endVersion);
    result.add(Params.DELTAS, serializeDeltas(version, history.getData()));
    result.addProperty(Params.HAS_MORE, history.hasMore());
    return result;
  }

  private JsonObject fetchHistories(JsonArray ids) throws IOException, SlobNotFoundException,
      AccessDeniedException {
    SlobStore store = slobFacilities.getSlobStore();
    JsonObject toRtn = new JsonObject();
    JsonArray msgs = new JsonArray();
    String token = null;
    for (JsonElement e : ids) {
      JsonObject obj = e.getAsJsonObject();
      ObjectId key = new ObjectId(obj.get(Params.ID).getAsString());
      long version = obj.get(Params.REVISION).getAsLong();
      Long endVersion =
          obj.has(Params.END_REVISION) ? obj.get(Params.END_REVISION).getAsLong() : null;
      JsonObject msg;
      HistoryResult history = store.loadHistory(key, version, endVersion);
      msg =
          LocalMutationProcessor.jsonBroadcastData(key.toString(), serializeDeltas(version, history
              .getData()));
      // msg.addProperty(Params.REVISION, r.getVersion());
      msgs.add(msg);
    }
    if (token != null) {
      toRtn.addProperty(Params.TOKEN, token);
    }
    toRtn.add(Params.DELTAS, msgs);
    return toRtn;
  }

  private JsonArray serializeDeltas(long startVersion, List<Delta<String>> entries) {
    JsonArray history = new JsonArray();
    int index = 0;
    for (Delta<String> data : entries) {
      history.add(DeltaSerializer.dataToClientJson(data, startVersion + index + 1));
      index++;
    }
    return history;
  }
}
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

import com.goodow.realtime.server.model.ObjectSession;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class ServerMutateRequest {
  public static ServerMutateRequest fromJson(String json) {
    return new Gson().fromJson(json, ServerMutateRequest.class);
  }

  private long version;
  private List<String> payload;
  private ObjectSession session;

  public List<String> getPayload() {
    return payload;
  }

  public ObjectSession getSession() {
    return session;
  }

  public long getVersion() {
    return version;
  }

  public void setDeltas(String deltas) {
    JsonArray jsonArray = new JsonParser().parse(deltas).getAsJsonArray();
    payload = new ArrayList<String>();
    for (JsonElement e : jsonArray) {
      payload.add(e.toString());
    }
  }

  public void setSession(ObjectSession session) {
    this.session = session;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  @Override
  public String toString() {
    Gson gson = new Gson();
    return gson.toJson(this);
  }
}
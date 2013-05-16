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
import com.goodow.realtime.server.model.ObjectId;
import com.goodow.realtime.server.model.ObjectSession;
import com.goodow.realtime.server.model.SessionId;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.walkaround.slob.server.AccessDeniedException;
import com.google.walkaround.slob.server.MutateResult;
import com.google.walkaround.slob.server.ServerMutateRequest;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.server.SlobNotFoundException;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.BadRequestException;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Submits a delta to a object.
 */
public class SaveHandler extends AbstractHandler {

  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(SaveHandler.class.getName());

  @Inject
  SlobFacilities slobFacilities;

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String sid = requireParameter(req, Constants.Params.SESSION_ID);
    String key = requireParameter(req, Constants.Params.ID);
    JsonObject payload = new JsonParser().parse(req.getParameter("")).getAsJsonObject();
    long version = payload.get(Params.REVISION).getAsLong();
    String deltas = payload.get(Params.CHANGES).toString();

    ServerMutateRequest mutateRequest = new ServerMutateRequest();
    mutateRequest.setSession(new ObjectSession(new ObjectId(key), new SessionId(sid)));
    mutateRequest.setVersion(version);
    mutateRequest.setDeltas(deltas);

    MutateResult res;
    try {
      res = slobFacilities.getSlobStore().mutateObject(mutateRequest);
    } catch (SlobNotFoundException e) {
      throw new BadRequestException("Object not found or access denied", e);
    } catch (AccessDeniedException e) {
      throw new BadRequestException("Object not found or access denied", e);
    }

    JsonObject json = new JsonObject();
    json.addProperty(Constants.Params.REVISION, res.getResultingVersion());
    resp.setContentType("application/json");
    Util.writeJsonResult(resp.getWriter(), json.toString());
  }
}
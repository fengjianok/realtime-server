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
package com.goodow.realtime.server.servlet;

import com.google.gson.JsonParseException;
import com.google.inject.Inject;
import com.google.walkaround.slob.server.AffinityMutationProcessor;
import com.google.walkaround.slob.server.ServerMutateRequest;
import com.google.walkaround.slob.server.ServerMutateResponse;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.server.StoreAccessChecker;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.BadRequestException;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Directly mutates the data store. Looks for the "X-Walkaround-Trusted" header.
 */
public class StoreMutateHandler extends AbstractHandler {

  private static final Logger log = Logger.getLogger(StoreMutateHandler.class.getName());

  @Inject
  StoreAccessChecker accessChecker;
  @Inject
  SlobFacilities slobFacilities;

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    accessChecker.checkPermittedStoreRequest(req);
    String requestString = requireParameter(req, AffinityMutationProcessor.SERVER_MUTATE_REQUEST);
    ServerMutateRequest mutateRequest;
    try {
      mutateRequest = ServerMutateRequest.fromJson(requestString);
    } catch (JsonParseException e) {
      throw new BadRequestException("Failed to parse request: " + requestString, e);
    }
    ServerMutateResponse result =
        slobFacilities.getLocalMutationProcessor().mutateObject(mutateRequest);
    log.info("Success @" + result.getResultingVersion());

    resp.setStatus(200);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    resp.getWriter().print("OK" + result.toString());
  }
}
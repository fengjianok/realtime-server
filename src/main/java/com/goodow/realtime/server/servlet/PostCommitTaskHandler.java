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

import com.goodow.realtime.server.model.ObjectId;

import com.google.inject.Inject;
import com.google.walkaround.slob.server.PostCommitAction;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.BadRequestException;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Task queue handler that processes {@link PostCommitAction}s.
 */
public class PostCommitTaskHandler extends AbstractHandler {

  public static final String SLOB_ID_PARAM = "slob_id";
  private static final Logger log = Logger.getLogger(PostCommitTaskHandler.class.getName());

  @Inject
  SlobFacilities slobFacilities;

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    log.info(this + ": doPost()");
    if (req.getHeader("X-AppEngine-QueueName") == null) {
      throw new BadRequestException();
    }
    ObjectId slobId = new ObjectId(requireParameter(req, SLOB_ID_PARAM));
    slobFacilities.getPostCommitActionScheduler().taskInvoked(slobId);
  }
}
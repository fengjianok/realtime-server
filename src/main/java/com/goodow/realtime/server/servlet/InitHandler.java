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

import com.goodow.realtime.channel.rpc.Constants.Params;
import com.goodow.realtime.id.IdGenerator;

import com.google.gson.JsonObject;
import com.google.walkaround.util.server.servlet.AbstractHandler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class InitHandler extends AbstractHandler {
  // public static final String nocacheJs = Util.slurpRequired("moon/moon.nocache.js");

  public static final JsonObject initVars() {
    JsonObject obj = new JsonObject();
    obj.addProperty(Params.SESSION_ID, new IdGenerator().next(8));
    return obj;
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException,
      ServletException {
    JsonObject obj = initVars();
    resp.setContentType("application/json");
    resp.getWriter().print(obj.toString());
  }
}
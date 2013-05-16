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
package com.goodow.realtime.server.model;

import com.goodow.realtime.DocumentBridge;
import com.goodow.realtime.operation.RealtimeOperation;
import com.goodow.realtime.operation.RealtimeTransformer;
import com.goodow.realtime.operation.TransformException;
import com.goodow.realtime.util.Pair;

import com.google.inject.Inject;
import com.google.walkaround.slob.shared.InvalidSnapshot;
import com.google.walkaround.slob.shared.SlobModel;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import elemental.json.Json;
import elemental.json.JsonException;
import elemental.json.JsonValue;
import elemental.util.ArrayOf;
import elemental.util.Collections;

public class RealtimeSlobAdapter implements SlobModel {
  class RealtimeSlob implements Slob {
    private final DocumentBridge bridge;

    RealtimeSlob(JsonValue json) {
      this.bridge = transformer.createSnapshot(json);
    }

    @Override
    public void apply(Delta<String> change) throws DeltaRejected {
      RealtimeOperation<?> op;
      try {
        op =
            transformer.createOperation(Json.instance().parse(change.getPayload()), "fake user",
                change.getSessionId().getId());
      } catch (JsonException e) {
        throw new DeltaRejected("Malformed op: " + change, e);
      }
      try {
        bridge.consume(op);
      } catch (RuntimeException e) {
        throw new DeltaRejected("Invalid op: " + op, e);
      }
    }

    @Nullable
    @Override
    public String snapshot() {
      return bridge.toString();
    }
  }

  private final RealtimeTransformer transformer;

  @Inject
  RealtimeSlobAdapter(RealtimeTransformer transformer) {
    this.transformer = transformer;
  }

  @Override
  public Slob create(@Nullable String snapshot) throws InvalidSnapshot {
    if (snapshot == null) {
      return new RealtimeSlob(Json.createArray());
    } else {
      try {
        return new RealtimeSlob(Json.instance().parse(snapshot));
      } catch (JsonException e) {
        throw new InvalidSnapshot(e);
      }
    }
  }

  @Override
  public List<String> transform(List<Delta<String>> clientOps, List<Delta<String>> serverOps)
      throws DeltaRejected {
    try {
      Pair<ArrayOf<RealtimeOperation<?>>, ArrayOf<RealtimeOperation<?>>> pair =
          transformer.transform(deserializeOps(serverOps), deserializeOps(clientOps));
      ArrayOf<RealtimeOperation<?>> cOps = pair.second;
      ArrayList<String> toRtn = new ArrayList<String>(cOps.length());
      for (int i = 0, len = cOps.length(); i < len; i++) {
        toRtn.add(cOps.get(i).toString());
      }
      return toRtn;
    } catch (TransformException e) {
      throw new DeltaRejected(e);
    }
  }

  private ArrayOf<RealtimeOperation<?>> deserializeOps(List<Delta<String>> changes)
      throws DeltaRejected {
    ArrayOf<RealtimeOperation<?>> ops = Collections.arrayOf();
    for (int i = 0; i < changes.size(); i++) {
      RealtimeOperation<?> op;
      try {
        Delta<String> delta = changes.get(i);
        op =
            transformer.createOperation(Json.instance().create(delta.getPayload()), "fake user",
                delta.getSessionId().toString());
      } catch (JsonException e) {
        throw new DeltaRejected(e);
      }
      ops.push(op);
    }
    return ops;
  }
}
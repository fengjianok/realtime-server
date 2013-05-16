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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public class ObjectSession {

  private final ObjectId objectId;
  private final SessionId sessionId;

  public ObjectSession(ObjectId objectId, SessionId sessionId) {
    this.objectId = Preconditions.checkNotNull(objectId, "Null objectId");
    this.sessionId = Preconditions.checkNotNull(sessionId, "Null clientId");
  }

  @Override
  public final boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ObjectSession)) {
      return false;
    }
    ObjectSession other = (ObjectSession) o;
    return Objects.equal(objectId, other.objectId) && Objects.equal(sessionId, other.sessionId);
  }

  public ObjectId getObjectId() {
    return objectId;
  }

  public SessionId getSessionId() {
    return sessionId;
  }

  @Override
  public final int hashCode() {
    return Objects.hashCode(objectId, sessionId);
  }

  @Override
  public String toString() {
    return "ObjectSession(" + objectId + ", " + sessionId + ")";
  }
}
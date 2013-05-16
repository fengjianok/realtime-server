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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import java.io.Serializable;

/**
 * Type-safe wrapper around a raw object id.
 */
public final class ObjectId implements Serializable {
  private static final long serialVersionUID = 178314993219268135L;
  public static final String SEPERATE = "/";

  private final String kind;
  private final String id;

  public ObjectId(String key) {
    checkNotNull(key, "Null object key");
    int indexOf = key.indexOf('/');
    Preconditions.checkArgument(indexOf != -1 && indexOf < key.length() - 1);
    this.kind = key.substring(0, indexOf);
    this.id = key.substring(indexOf + 1);
  }

  public ObjectId(String kind, String id) {
    this.kind = Preconditions.checkNotNull(kind, "Null kind");;
    this.id = Preconditions.checkNotNull(id, "Null id");;
  }

  @Override
  public final boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ObjectId)) {
      return false;
    }
    ObjectId other = (ObjectId) o;
    return Objects.equal(id, other.id) && Objects.equal(kind, other.kind);
  }

  public String getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  @Override
  public final int hashCode() {
    return Objects.hashCode(kind, id);
  }

  @Override
  public String toString() {
    return kind + SEPERATE + id;
  }
}
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
package com.goodow.realtime.server;

import com.google.common.base.Preconditions;
import com.google.walkaround.util.server.flags.FlagDeclaration;
import com.google.walkaround.wave.server.Flag;

import java.lang.annotation.Annotation;

public enum FlagName implements FlagDeclaration {
  OBJECT_CHANNEL_EXPIRATION_SECONDS(Integer.class),
  STORE_SERVER(String.class),
  NUM_STORE_SERVERS(Integer.class),
  POST_COMMIT_ACTION_INTERVAL_MILLIS(Integer.class),
  SLOB_LOCAL_CACHE_EXPIRATION_MILLIS(Integer.class),
  ;

  // Stolen from com.google.inject.name.NamedImpl.
  static class FlagImpl implements Flag {
    private final FlagName value;

    FlagImpl(FlagName value) {
      Preconditions.checkNotNull(value, "Null value");
      this.value = value;
    }

    @Override public FlagName value() {
      return value;
    }

    @Override public int hashCode() {
      // This is specified in java.lang.Annotation.
      return 127 * "value".hashCode() ^ value.hashCode();
    }

    @Override public boolean equals(Object o) {
      if (!(o instanceof Flag)) {
        return false;
      }
      Flag other = (Flag) o;
      return value.equals(other.value());
    }

    @Override public String toString() {
      return "@Flag(" + value + ")";
    }

    @Override public Class<? extends Annotation> annotationType() {
      return Flag.class;
    }
  }

  private final String name;
  private final Class<?> type;

  private FlagName(Class<?> type) {
    this.name = name().toLowerCase();
    this.type = type;
  }

  @Override public Annotation getAnnotation() {
    return new FlagImpl(this);
  }

  @Override public String getName() {
    return name;
  }

  @Override public Class<?> getType() {
    return type;
  }
}
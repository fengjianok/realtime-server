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

/**
 * Thrown when a model change was rejected.
 * 
 * When this exception is thrown, the model must remain in the state prior to the rejected change,
 * ready for application of a different, valid change.
 */
public class DeltaRejected extends Exception {
  private static final long serialVersionUID = 866300980861599406L;

  public DeltaRejected() {
    super();
  }

  public DeltaRejected(String message) {
    super(message);
  }

  public DeltaRejected(String message, Throwable cause) {
    super(message, cause);
  }

  public DeltaRejected(Throwable cause) {
    super(cause);
  }
}
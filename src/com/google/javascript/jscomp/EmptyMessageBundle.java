/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.JsMessage.IdGenerator;
import org.jspecify.annotations.Nullable;

/**
 * An implementation of MessageBundle that has no translations.
 */
public final class EmptyMessageBundle implements MessageBundle {

  /** Gets a dummy message ID generator. */
  @Override
  public @Nullable IdGenerator idGenerator() {
    return null;
  }

  /** Returns null, to indicate it has no message replacements. */
  @Override
  public @Nullable JsMessage getMessage(String id) {
    return null;
  }

  /** Returns an empty list of messages. */
  @Override
  public ImmutableList<JsMessage> getAllMessages() {
    return ImmutableList.of();
  }
}

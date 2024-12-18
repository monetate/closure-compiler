/*
 * Copyright 2021 The Closure Compiler Authors.
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

package com.google.javascript.rhino;

import java.util.List;
import java.util.stream.Stream;

/**
 * A JS compatible implementation of the string pool.
 *
 * <p>All methods are basically no-ops because JS strings always use identity semantics.
 */
public final class RhinoStringPool {

  @SuppressWarnings("ReferenceEquality")
  public static boolean uncheckedEquals(String a, String b) {
    return a == b;
  }

  public static String addOrGet(String s) {
    return s;
  }

  private RhinoStringPool() {}

  /**
   * Stub implementation
   *
   * <p>Ok because it's only used for serialization/deserialization
   */
  public static final class LazyInternedStringList {
    public LazyInternedStringList(List<String> strings) {}

    public String get(int offset) {
      return null;
    }

    public Stream<String> stream() {
      return Stream.empty();
    }
  }
}

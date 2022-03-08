/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.util.concurrent;

import java.util.concurrent.locks.Lock;

/** Locked state of the lock. */
public class AutoCloseableReadLocked extends AutoCloseableLocked {

  private AutoCloseableReadLocked(Lock lock) {
    super(lock);
  }

  /** Lock the lock and return an object which unlocks on close. */
  static AutoCloseableReadLocked createFor(Lock lock) {
    return new AutoCloseableReadLocked(lock);
  }
}

/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.dac.options;

import com.dremio.options.Options;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.options.UserControlledOption;

/**
 * Set of reflection options that are controlled by user actions in the UI and other user-facing
 * APIs.
 */
@Options
public final class ReflectionUserOptions {
  /** Controls whether we will attempt to accelerate queries using reflections. */
  @UserControlledOption
  public static final BooleanValidator REFLECTION_ENABLE_SUBSTITUTION =
      new BooleanValidator("reflection.enable.substitutions", true);
}

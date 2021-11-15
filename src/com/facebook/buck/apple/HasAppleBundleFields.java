/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

public interface HasAppleBundleFields {
  Either<AppleBundleExtension, String> getExtension();

  SourcePath getInfoPlist();

  Optional<String> getProductName();

  Optional<String> getXcodeProductType();

  Optional<Boolean> getSkipCopyingSwiftStdlib();

  Optional<Boolean> getTrySkipCodeSigning();

  ImmutableMap<String, String> getInfoPlistSubstitutions();

  @Value.Default
  default AppleAssetCatalogsCompilationOptions getAssetCatalogsCompilationOptions() {
    return AppleAssetCatalogsCompilationOptions.builder().build();
  }
}
